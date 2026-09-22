package net.lichias.opencodechat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    private const val TIMEOUT_CONNECT = 15_000
    private const val TIMEOUT_READ = 120_000
    private const val CATALOG_URL = "https://models.dev/api.json"
    private const val USER_AGENT = "opencode-chat-android/1.0"
    private const val OPENROUTER_REFERER = "https://github.com/lichias/opencode-chat"
    private const val OPENROUTER_TITLE = "OpenCode Chat Android"

    // ------------------------------------------------------------------
    // Routing: which wire protocol does a model need?
    // Mirrors models.dev `provider.npm` + prefix heuristics used by
    // opencode's own gateway.
    // ------------------------------------------------------------------

    fun resolveRoute(modelId: String, npm: String?): ApiKind {
        val pkg = (npm ?: "").lowercase()
        if (pkg.contains("anthropic")) return ApiKind.MESSAGES
        if (pkg.contains("google")) return ApiKind.GEMINI
        if (pkg == "@ai-sdk/openai" || pkg.endsWith("/openai")) return ApiKind.RESPONSES
        val id = modelId.lowercase()
        if (id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-spark")) return ApiKind.RESPONSES
        if (id.startsWith("claude-")) return ApiKind.MESSAGES
        if (id.startsWith("gemini-")) return ApiKind.GEMINI
        return ApiKind.CHAT
    }

    private fun routeFromNpm(npm: String?, modelId: String): ApiKind = resolveRoute(modelId, npm)

    // ------------------------------------------------------------------
    // Model listing (live). Resilient: works without a key, parses both
    // OpenAI shapes, never throws.
    // ------------------------------------------------------------------

    fun listModels(baseUrl: String, apiKey: String): List<String> =
        listModelsFor(baseUrl, apiKey, emptyMap())

    fun listModelsFor(
        baseUrl: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): List<String> {
        val conn = openGet("$baseUrl/models", apiKey.ifBlank { null }, extraHeaders)
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseModelList(body)
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    fun parseModelList(body: String): List<String> = try {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            (0 until arr.length())
                .mapNotNull {
                    arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() }
                        ?: arr.optString(it).takeIf { id -> id.isNotEmpty() }
                }
                .distinct().sorted()
        } else {
            val data = JSONObject(trimmed).optJSONArray("data") ?: JSONArray()
            (0 until data.length())
                .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
                .distinct().sorted()
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------
    // models.dev catalog: efforts + routing + free flag.
    // ------------------------------------------------------------------

    /**
     * Per-model metadata from the models.dev catalog for the given provider
     * key ("opencode" = Zen, "opencode-go" = Go, "openrouter" = OpenRouter).
     */
    fun fetchCatalog(catalogKey: String): Map<String, ModelInfo> {
        val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_CONNECT
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            if (conn.responseCode !in 200..299) return emptyMap()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseCatalog(body, catalogKey)
        } catch (_: Exception) {
            emptyMap()
        } finally {
            conn.disconnect()
        }
    }

    fun parseCatalog(body: String, catalogKey: String): Map<String, ModelInfo> = try {
        val provider = JSONObject(body).optJSONObject(catalogKey) ?: return emptyMap()
        val defaultNpm = provider.optString("npm").takeIf { it.isNotEmpty() }
        val models = provider.optJSONObject("models") ?: return emptyMap()
        buildMap {
            val keys = models.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val m = models.optJSONObject(id) ?: continue
                val options = m.optJSONArray("reasoning_options") ?: JSONArray()
                var efforts: List<String> = emptyList()
                for (i in 0 until options.length()) {
                    val o = options.optJSONObject(i) ?: continue
                    if (o.optString("type") == "effort") {
                        val values = o.optJSONArray("values") ?: JSONArray()
                        efforts = (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotEmpty) }
                    }
                }
                val npm = m.optJSONObject("provider")?.optString("npm")?.takeIf { it.isNotEmpty() } ?: defaultNpm
                val cost = m.optJSONObject("cost")
                val freeByCost = cost != null &&
                    cost.optDouble("input", -1.0) == 0.0 &&
                    cost.optDouble("output", -1.0) == 0.0
                val freeByName = id.contains("free", ignoreCase = true) || id.contains(":free", ignoreCase = true)
                put(id, ModelInfo(id = id, efforts = efforts, apiKind = routeFromNpm(npm, id), isFree = freeByCost || freeByName))
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    // ------------------------------------------------------------------
    // Privacy: ZDR availability + training-on-prompts flags.
    //
    // Sources (all verified 2026-09-22):
    // - OpenRouter ZDR endpoints: GET https://openrouter.ai/api/v1/endpoints/zdr
    //   -> {data: [{model_id, ...}]}. 875 endpoints / ~311 model_ids at check
    //   time. Join key is `model_id`, which matches /api/v1/models `id` for
    //   248/444 models. NOTE: ZDR is per-endpoint, not per-model — a badge
    //   means "a ZDR endpoint exists", not "this request was ZDR". Guarantee
    //   routing with provider {zdr: true, data_collection: "deny"} (see
    //   applyOpenRouterPrivacy below, enabled by the ZDR-only toggle).
    // - OpenRouter provider training/retention: GET
    //   https://openrouter.ai/api/frontend/v1/all-providers
    //   -> {data: [{name, dataPolicy: {training, retainsPrompts,
    //   retentionDays}}]}. 4/88 providers trained at check time
    //   (DeepSeek, Liquid, Thinking Machines, Nvidia). Matched conservatively
    //   against the model-id owner prefix: the routed endpoint may differ.
    // - Zen privacy exceptions: https://opencode.ai/docs/zen#privacy (paid
    //   models = zero-retention + no training; free/contributor models may
    //   train; OpenAI/Anthropic-served requests retained 30 days).
    // - Go privacy table: https://opencode.ai/docs/go#privacy (only the two
    //   Muse Contributor models train; Grok + GPT Luna retained 30 days).
    // Docs-derived lists are hardcoded and can drift — treat UNKNOWN as
    // "no signal", never as "safe".
    // ------------------------------------------------------------------

    private const val ZDR_URL = "https://openrouter.ai/api/v1/endpoints/zdr"
    private const val PROVIDERS_URL = "https://openrouter.ai/api/frontend/v1/all-providers"

    /** Models Zen serves that may train on prompts (docs privacy exceptions). */
    private val ZEN_TRAINS = setOf(
        "big-pickle",
        "mimo-v2.6-flash-free",
        "mimo-v2.5-free",
        "ling-3.0-flash-fin-free",
        "nemotron-3-ultra-free",
        "nemotron-3.5-lightning-free",
        "muse-spark-1.3-contributor-free",
    )

    /** Models Go serves that train on prompts (docs privacy table). */
    private val GO_TRAINS = setOf(
        "muse-spark-1.3-contributor",
        "muse-spark-1.2-contributor",
    )

    fun fetchOpenRouterZdrIds(): Set<String> {
        return try {
            val conn = URL(ZDR_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_CONNECT
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode !in 200..299) return emptySet()
                parseZdrIds(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parseZdrIds(body: String): Set<String> = try {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        buildSet {
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.optString("model_id")
                    ?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    } catch (_: Exception) {
        emptySet()
    }

    /** Lowercased provider names whose dataPolicy.training == true. */
    fun fetchOpenRouterTrainingPrefixes(): Set<String> {
        return try {
            val conn = URL(PROVIDERS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_CONNECT
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode !in 200..299) return emptySet()
                parseTrainingPrefixes(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parseTrainingPrefixes(body: String): Set<String> = try {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        buildSet {
            for (i in 0 until data.length()) {
                val p = data.optJSONObject(i) ?: continue
                if (p.optJSONObject("dataPolicy")?.optBoolean("training") == true) {
                    val name = p.optString("name").lowercase().replace(" ", "")
                    if (name.isNotEmpty()) {
                        add(name)
                        // "Thinking Machines" -> also match "thinkingmachines/..." ids.
                        add(name.replace(" ", ""))
                    }
                }
            }
        }
    } catch (_: Exception) {
        emptySet()
    }

    fun privacyForOpenRouter(
        modelId: String,
        zdrIds: Set<String>,
        trainingPrefixes: Set<String>,
    ): Privacy {
        if (modelId in zdrIds) return Privacy.ZDR
        val owner = modelId.substringBefore("/").lowercase().replace(" ", "")
        if (owner in trainingPrefixes) return Privacy.TRAINS
        return Privacy.UNKNOWN
    }

    fun privacyForZen(modelId: String): Privacy {
        val id = modelId.lowercase()
        // Contributor tier exists to train on prompts, at any version.
        if ("contributor" in id || modelId in ZEN_TRAINS) return Privacy.TRAINS
        // OpenAI/Anthropic-served Zen requests are retained 30 days for abuse
        // monitoring (no training) — not ZDR, so no badge either way.
        if (id.startsWith("gpt-") || id.startsWith("claude-")) return Privacy.UNKNOWN
        // Fail closed: the free-tier list in models.dev has grown past the
        // docs privacy exceptions, so an unlisted -free model gets no badge
        // rather than a shield we cannot vouch for.
        if (id.endsWith("-free") || ":free" in id) return Privacy.UNKNOWN
        return Privacy.ZDR
    }

    fun privacyForGo(modelId: String): Privacy {
        val id = modelId.lowercase()
        if ("contributor" in id || modelId in GO_TRAINS) return Privacy.TRAINS
        // Grok 4.7/4.6 + GPT 5.6 Luna: 30-day abuse retention, no training.
        if (id.startsWith("grok-") || id == "gpt-5.6-luna") return Privacy.UNKNOWN
        if (id.endsWith("-free") || ":free" in id) return Privacy.UNKNOWN
        return Privacy.ZDR
    }

    // ------------------------------------------------------------------
    // Chat streaming with automatic per-model routing + fallback.
    // ------------------------------------------------------------------

    data class ChatRequest(
        val provider: Provider,
        val apiKey: String,
        val model: String,
        val effort: String?,
        val history: List<ChatMessage>,
        val catalog: Map<String, ModelInfo> = emptyMap(),
        val sessionId: String? = null,
        /** When true (OpenRouter only): send provider {zdr: true, data_collection: "deny"}. */
        val zdrOnly: Boolean = false,
    )

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        effort: String?,
        history: List<ChatMessage>,
    ): Flow<String> = flow {
        // Legacy entry point: route by heuristics, fall back across protocols.
        val req = ChatRequest(
            provider = providerForBase(baseUrl),
            apiKey = apiKey,
            model = model,
            effort = effort,
            history = history,
        )
        streamResilient(req).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun streamResilient(req: ChatRequest): Flow<String> = flow {
        val primary = req.catalog[req.model]?.apiKind ?: heuristicKind(req.provider, req.model)
        val order = buildAttemptOrder(primary, req.model)
        val failures = mutableListOf<String>()
        for (kind in order) {
            try {
                val inner: Flow<String> = when (kind) {
                    ApiKind.CHAT -> streamChatCompletions(req)
                    ApiKind.RESPONSES -> streamResponses(req)
                    ApiKind.MESSAGES -> streamMessages(req)
                    ApiKind.GEMINI -> streamGemini(req)
                }
                inner.collect { piece ->
                    emit(piece)
                }
                // Success — stop trying other protocols.
                return@flow
            } catch (e: HttpError) {
                failures.add("${kind.name} ${e.message}")
                if (!e.retryableWithOtherProtocol) throw e.toFriendly()
                // else try next protocol
            }
        }
        throw RuntimeException(
            "All endpoints failed for ${req.model}: " + failures.joinToString(" | ").take(600),
        )
    }.flowOn(Dispatchers.IO)

    private fun providerForBase(baseUrl: String): Provider =
        Provider.entries.firstOrNull { it.baseUrl == baseUrl } ?: Provider.ZEN

    private fun heuristicKind(provider: Provider, model: String): ApiKind {
        if (provider == Provider.OPENROUTER) return ApiKind.CHAT
        return resolveRoute(model, null)
    }

    private fun buildAttemptOrder(primary: ApiKind, model: String): List<ApiKind> {
        if (primary == ApiKind.GEMINI || model.lowercase().startsWith("gemini-")) {
            return listOf(ApiKind.GEMINI, ApiKind.CHAT, ApiKind.RESPONSES, ApiKind.MESSAGES).distinct()
        }
        return (listOf(primary) + listOf(ApiKind.CHAT, ApiKind.RESPONSES, ApiKind.MESSAGES)).distinct()
    }

    /** Enforces ZDR routing for OpenRouter when the ZDR-only toggle is on. */
    private fun JSONObject.applyOpenRouterPrivacy(req: ChatRequest) {
        if (req.provider == Provider.OPENROUTER && req.zdrOnly) {
            put("provider", JSONObject().put("zdr", true).put("data_collection", "deny"))
        }
    }

    // ---------------- chat/completions ----------------

    private fun streamChatCompletions(req: ChatRequest): Flow<String> = flow {
        val url = chatUrl(req, ApiKind.CHAT)
        val body = JSONObject().apply {
            put("model", req.model)
            put("stream", true)
            put("messages", buildMessages(req.history))
            if (req.effort != null && (req.provider == Provider.ZEN || req.provider == Provider.GO)) {
                put("reasoning_effort", req.effort)
            }
            applyOpenRouterPrivacy(req)
        }
        val conn = openPost(url, req, ApiKind.CHAT)
        try {
            conn.getOutputStream().use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkResponse(conn, req, ApiKind.CHAT)
            if (!isEventStream(conn)) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseChatNonStream(text)?.let { emit(it) }
                return@flow
            }
            readSse(conn) { _, payload ->
                if (payload == "[DONE]") return@readSse null
                parseDelta(payload)
            }.collect { emit(it) }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- responses ----------------

    private fun streamResponses(req: ChatRequest): Flow<String> = flow {
        val url = chatUrl(req, ApiKind.RESPONSES)
        val body = JSONObject().apply {
            put("model", req.model)
            put("stream", true)
            put("input", buildResponsesInput(req.history))
            if (req.effort != null) {
                put("reasoning", JSONObject().put("effort", req.effort))
            }
            applyOpenRouterPrivacy(req)
        }
        val conn = openPost(url, req, ApiKind.RESPONSES)
        try {
            conn.getOutputStream().use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkResponse(conn, req, ApiKind.RESPONSES)
            if (!isEventStream(conn)) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseResponsesNonStream(text)?.let { emit(it) }
                return@flow
            }
            readSse(conn) { _, payload ->
                if (payload == "[DONE]") return@readSse null
                parseResponsesDelta(payload) ?: parseDelta(payload)
            }.collect { emit(it) }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- messages (Anthropic) ----------------

    private fun streamMessages(req: ChatRequest): Flow<String> = flow {
        val url = chatUrl(req, ApiKind.MESSAGES)
        val body = buildAnthropicBody(req).apply { applyOpenRouterPrivacy(req) }
        val conn = openPost(url, req, ApiKind.MESSAGES)
        try {
            conn.getOutputStream().use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkResponse(conn, req, ApiKind.MESSAGES)
            if (!isEventStream(conn)) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseAnthropicNonStream(text)?.let { emit(it) }
                return@flow
            }
            readSse(conn) { event, payload ->
                parseAnthropicDelta(event, payload)
            }.collect { emit(it) }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- gemini ----------------

    private fun streamGemini(req: ChatRequest): Flow<String> = flow {
        val url = chatUrl(req, ApiKind.GEMINI)
        val body = JSONObject().apply {
            put("contents", buildGeminiContents(req.history))
            put("generationConfig", JSONObject().put("maxOutputTokens", 4096))
            applyOpenRouterPrivacy(req)
        }
        val conn = openPost(url, req, ApiKind.GEMINI)
        try {
            conn.getOutputStream().use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            checkResponse(conn, req, ApiKind.GEMINI)
            if (!isEventStream(conn)) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parseGeminiNonStream(text)?.let { emit(it) }
                return@flow
            }
            readSse(conn) { _, payload ->
                if (payload == "[DONE]") return@readSse null
                parseGeminiDelta(payload)
            }.collect { emit(it) }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // URLs + headers
    // ------------------------------------------------------------------

    private fun chatUrl(req: ChatRequest, kind: ApiKind): String {
        val base = req.provider.baseUrl.trimEnd('/')
        return when (kind) {
            ApiKind.CHAT -> "$base/chat/completions"
            ApiKind.RESPONSES -> "$base/responses"
            ApiKind.MESSAGES -> "$base/messages"
            ApiKind.GEMINI -> "$base/models/${req.model}:streamGenerateContent?alt=sse"
        }
    }

    private fun bearerToken(req: ChatRequest): String? =
        req.apiKey.takeIf { it.isNotBlank() }

    private fun openGet(url: String, apiKey: String?, extra: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (!apiKey.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            extra.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = TIMEOUT_CONNECT
            readTimeout = 30_000
        }

    private fun openPost(url: String, req: ChatRequest, kind: ApiKind): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            val token = bearerToken(req)
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
                // Anthropic + Google SDKs use their own key headers; send them too
                // so the Zen gateway accepts either form.
                if (kind == ApiKind.MESSAGES) setRequestProperty("x-api-key", token)
                if (kind == ApiKind.GEMINI) setRequestProperty("x-goog-api-key", token)
            }
            if (kind == ApiKind.MESSAGES) {
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            if (req.provider == Provider.OPENROUTER) {
                setRequestProperty("HTTP-Referer", OPENROUTER_REFERER)
                setRequestProperty("X-Title", OPENROUTER_TITLE)
            }
            if ((req.provider == Provider.ZEN || req.provider == Provider.GO) && req.sessionId != null) {
                setRequestProperty("x-opencode-session", req.sessionId)
            }
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = TIMEOUT_CONNECT
            readTimeout = TIMEOUT_READ
        }

    private fun open(url: String, apiKey: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = TIMEOUT_CONNECT
            readTimeout = TIMEOUT_READ
        }

    private fun isEventStream(conn: HttpURLConnection): Boolean =
        (conn.getHeaderField("Content-Type") ?: "").contains("event-stream", ignoreCase = true)

    // ------------------------------------------------------------------
    // SSE reader handling both OpenAI (data: only) and Anthropic
    // (event: + data:) framing.
    // ------------------------------------------------------------------

    private fun readSse(
        conn: HttpURLConnection,
        parse: (event: String?, payload: String) -> String?,
    ): Flow<String> = flow {
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        var pendingEvent: String? = null
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val raw = line ?: break
            val l = raw.trim()
            if (l.isEmpty()) {
                pendingEvent = null
                continue
            }
            if (l.startsWith(":")) continue // keep-alive comment
            if (l.startsWith("event:")) {
                pendingEvent = l.removePrefix("event:").trim().takeIf { it.isNotEmpty() }
                continue
            }
            if (!l.startsWith("data:")) {
                // Some gateways emit bare JSON lines; try them as chat deltas.
                if (l.startsWith("{")) {
                    parseDelta(l)?.let { emit(it) }
                }
                continue
            }
            val payload = l.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) {
                pendingEvent = null
                continue
            }
            val piece = try {
                parse(pendingEvent, payload)
            } catch (_: Exception) {
                null
            }
            pendingEvent = null
            if (piece != null) emit(piece)
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Request body builders
    // ------------------------------------------------------------------

    private fun buildMessages(history: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in history) {
            val msg = JSONObject().put("role", m.role)
            if (m.imageDataUrl != null && m.role == "user") {
                msg.put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", m.text.ifEmpty { "Describe this image." }))
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", m.imageDataUrl)),
                        ),
                )
            } else {
                msg.put("content", m.text)
            }
            arr.put(msg)
        }
        return arr
    }

    private fun buildResponsesInput(history: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in history) {
            val role = when (m.role) {
                "assistant" -> "assistant"
                "system", "developer" -> "system"
                else -> "user"
            }
            if (m.imageDataUrl != null && role == "user") {
                val parts = JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", m.text.ifEmpty { "Describe this image." }))
                    .put(JSONObject().put("type", "input_image").put("image_url", m.imageDataUrl))
                arr.put(JSONObject().put("role", role).put("content", parts))
            } else {
                arr.put(JSONObject().put("role", role).put("content", m.text))
            }
        }
        return arr
    }

    private fun buildAnthropicBody(req: ChatRequest): JSONObject {
        var system: String? = null
        val messages = JSONArray()
        for (m in req.history) {
            if (m.role == "system") {
                system = if (system == null) m.text else system + "\n\n" + m.text
                continue
            }
            val role = if (m.role == "assistant") "assistant" else "user"
            if (m.imageDataUrl != null && role == "user") {
                val (mime, data) = splitDataUrl(m.imageDataUrl)
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text.ifEmpty { "Describe this image." }))
                    .put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", mime)
                                .put("data", data),
                        ),
                    )
                messages.put(JSONObject().put("role", role).put("content", content))
            } else {
                messages.put(JSONObject().put("role", role).put("content", m.text))
            }
        }
        return JSONObject().apply {
            put("model", req.model)
            put("max_tokens", 4096)
            put("stream", true)
            put("messages", messages)
            if (!system.isNullOrBlank()) put("system", system)
        }
    }

    private fun buildGeminiContents(history: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in history) {
            val role = when (m.role) {
                "assistant", "model" -> "model"
                else -> "user"
            }
            val parts = JSONArray()
            if (m.text.isNotEmpty()) parts.put(JSONObject().put("text", m.text))
            if (m.imageDataUrl != null && m.role == "user") {
                val (mime, data) = splitDataUrl(m.imageDataUrl)
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject().put("mimeType", mime).put("data", data),
                    ),
                )
            }
            if (parts.length() == 0) parts.put(JSONObject().put("text", " "))
            arr.put(JSONObject().put("role", role).put("parts", parts))
        }
        if (arr.length() == 0) {
            arr.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Hello"))))
        }
        return arr
    }

    private fun splitDataUrl(dataUrl: String): Pair<String, String> {
        // data:<mime>;base64,<data>
        val header = dataUrl.substringBefore(",", "")
        val data = dataUrl.substringAfter(",", "")
        val mime = header.substringAfter("data:", "image/jpeg").substringBefore(";")
        return mime to data
    }

    // ------------------------------------------------------------------
    // Response parsers
    // ------------------------------------------------------------------

    private fun parseDelta(payload: String): String? = try {
        val obj = JSONObject(payload)
        obj.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?.optString("content")
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun parseChatNonStream(body: String): String? = try {
        val obj = JSONObject(body)
        obj.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
            choice.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }
                ?: choice.optJSONObject("delta")?.optString("content")?.takeIf { it.isNotEmpty() }
                ?: choice.optString("text").takeIf { it.isNotEmpty() }
        } ?: obj.optString("output_text").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun parseResponsesDelta(payload: String): String? = try {
        val obj = JSONObject(payload)
        when (obj.optString("type")) {
            "response.output_text.delta" -> obj.optString("delta").takeIf { it.isNotEmpty() }
            "response.output_text.done" -> {
                val t = obj.optString("text")
                // Done events repeat the full text; only emit if it looks incremental-safe.
                // Emit nothing here to avoid duplicating the whole answer — the deltas
                // already carried it. Return null unless no deltas were seen (handled
                // by non-stream fallback).
                null
            }
            "response.completed" -> {
                // Final snapshot; deltas already streamed. Emit nothing to avoid dupes.
                null
            }
            else -> {
                // Unknown responses event: try chat-shape and output-shape fallbacks.
                parseDelta(payload)
                    ?: obj.optJSONObject("response")?.let { r ->
                        r.optJSONArray("output")?.let { out ->
                            buildString {
                                for (i in 0 until out.length()) {
                                    val item = out.optJSONObject(i) ?: continue
                                    if (item.optString("type") != "message") continue
                                    val content = item.optJSONArray("content") ?: continue
                                    for (j in 0 until content.length()) {
                                        val part = content.optJSONObject(j) ?: continue
                                        if (part.optString("type") == "output_text") {
                                            append(part.optString("text"))
                                        }
                                    }
                                }
                            }.takeIf { it.isNotEmpty() }
                        }
                    }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun parseResponsesNonStream(body: String): String? = try {
        val obj = JSONObject(body)
        // Responses non-stream: {output: [{type: message, content: [{type: output_text, text}]}]}
        val out = obj.optJSONArray("output")
            ?: obj.optJSONObject("response")?.optJSONArray("output")
        if (out != null) {
            buildString {
                for (i in 0 until out.length()) {
                    val item = out.optJSONObject(i) ?: continue
                    if (item.optString("type") != "message") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") append(part.optString("text"))
                    }
                }
            }.takeIf { it.isNotEmpty() } ?: parseChatNonStream(body)
        } else {
            parseChatNonStream(body) ?: obj.optString("output_text").takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    private fun parseAnthropicDelta(event: String?, payload: String): String? {
        return try {
            val obj = JSONObject(payload)
            val type = obj.optString("type").takeIf { it.isNotEmpty() } ?: event ?: return null
            if (type != "content_block_delta") return null
            val delta = obj.optJSONObject("delta") ?: return null
            if (delta.optString("type") == "text_delta") {
                delta.optString("text").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAnthropicNonStream(body: String): String? {
        return try {
            val obj = JSONObject(body)
            val content = obj.optJSONArray("content") ?: return null
            buildString {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text"))
                }
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGeminiDelta(payload: String): String? {
        return try {
            val obj = JSONObject(payload)
            val candidates = obj.optJSONArray("candidates") ?: return null
            buildString {
                for (i in 0 until candidates.length()) {
                    val content = candidates.optJSONObject(i)?.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue
                    for (j in 0 until parts.length()) {
                        append(parts.optJSONObject(j)?.optString("text") ?: "")
                    }
                }
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGeminiNonStream(body: String): String? = parseGeminiDelta(body)

    // ------------------------------------------------------------------
    // Errors with protocol-fallback awareness.
    // ------------------------------------------------------------------

    class HttpError(
        val status: Int,
        val snippet: String,
        val retryableWithOtherProtocol: Boolean,
    ) : RuntimeException("HTTP $status: $snippet") {
        fun toFriendly(): RuntimeException = RuntimeException(friendlyMessage(status, snippet))
    }

    private fun checkResponse(conn: HttpURLConnection, req: ChatRequest, kind: ApiKind) {
        if (conn.responseCode in 200..299) return
        val err = runCatching {
            (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.take(800) ?: ""
        val retryable = when (conn.responseCode) {
            400, 404, 405, 422, 501 -> true
            else -> false
        }
        throw HttpError(conn.responseCode, err.take(300), retryable)
    }

    fun friendlyMessage(status: Int, snippet: String): String {
        val short = snippet.take(220)
        return when (status) {
            401 -> "Invalid or expired key (401). Check the ${"key"} in Settings. $short"
            402 -> "Out of credits (402). Top up your balance. $short"
            403 -> "Forbidden (403). The key lacks access or the model is disabled for this workspace. $short"
            404 -> "Model or endpoint not found (404). It may be renamed or deprecated — refresh the model list. $short"
            429 -> "Rate limited (429). Wait a moment and try again. $short"
            in 500..599 -> "Server error ($status). Try again or pick another model. $short"
            else -> "HTTP $status: $short"
        }
    }
}
