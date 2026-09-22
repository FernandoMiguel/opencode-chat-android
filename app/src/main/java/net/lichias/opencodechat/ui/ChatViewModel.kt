package net.lichias.opencodechat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lichias.opencodechat.data.ApiClient
import net.lichias.opencodechat.data.ChatMessage
import net.lichias.opencodechat.data.ModelInfo
import net.lichias.opencodechat.data.Privacy
import net.lichias.opencodechat.data.Provider
import net.lichias.opencodechat.data.Session
import net.lichias.opencodechat.data.Store

class ChatViewModel(private val store: Store) : ViewModel() {

    val sessions = mutableStateListOf<Session>()
    var currentId by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var streamingText by mutableStateOf<String?>(null)
        private set

    var provider by mutableStateOf(store.provider)
        private set
    var effort by mutableStateOf(store.effort)
        private set
    var model by mutableStateOf(store.model)
        private set
    var apiKey by mutableStateOf(store.loadApiKey(store.provider))
        private set
    var favorites by mutableStateOf(store.favorites)
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set
    var catalog by mutableStateOf<Map<String, ModelInfo>>(emptyMap())
        private set
    var fontScale by mutableStateOf(store.fontScale)
        private set
    var showFreeOnly by mutableStateOf(store.showFreeOnly)
        private set
    var showZdrOnly by mutableStateOf(store.showZdrOnly)
        private set
    var pendingImage by mutableStateOf<String?>(null)
        private set

    init {
        sessions.addAll(store.loadSessions())
        currentId = sessions.firstOrNull()?.id
        refreshModels()
    }

    val current: Session?
        get() = sessions.firstOrNull { it.id == currentId }

    fun isFree(id: String): Boolean =
        catalog[id]?.isFree ?: id.contains("free", ignoreCase = true)

    fun freeCount(): Int = orderedModels(unfiltered = true).count { isFree(it) }

    fun privacyOf(id: String): Privacy = catalog[id]?.privacy ?: Privacy.UNKNOWN

    fun zdrCount(): Int = orderedModels(unfiltered = true).count { privacyOf(it) == Privacy.ZDR }

    fun trainsCount(): Int = orderedModels(unfiltered = true).count { privacyOf(it) == Privacy.TRAINS }

    fun orderedModels(unfiltered: Boolean = false): List<String> {
        val base = models.ifEmpty { Store.DEFAULT_MODELS }
        var effective = if (!unfiltered && showFreeOnly) base.filter { isFree(it) } else base
        if (!unfiltered && showZdrOnly) {
            val zdr = effective.filter { privacyOf(it) == Privacy.ZDR }
            // Like the free filter, never strand the user with zero options:
            // fall back when nothing matches.
            if (zdr.isNotEmpty()) effective = zdr
        }
        // If the free filter empties the list, fall back to the full list so the
        // picker never strands the user with zero options.
        if (!unfiltered && showFreeOnly && effective.isEmpty()) effective = base
        val pinned = favorites.filter { it in effective }
        return pinned + effective.filter { it !in favorites }.sorted()
    }

    fun refreshModels() {
        if (modelsLoading) return
        modelsLoading = true
        modelsError = null
        viewModelScope.launch {
            try {
                refreshKeyModels()
            } finally {
                modelsLoading = false
                normalizeEffort()
                val ordered = orderedModels()
                if (ordered.isNotEmpty() && model !in ordered) {
                    selectModel(ordered.first())
                }
            }
        }
    }

    private suspend fun refreshKeyModels() {
        val key = apiKey
        val live = withContext(Dispatchers.IO) {
            ApiClient.listModelsFor(provider.baseUrl, key)
        }
        var cat = withContext(Dispatchers.IO) { ApiClient.fetchCatalog(provider.catalogKey) }
        cat = withContext(Dispatchers.IO) { withPrivacy(provider, cat) }
        catalog = cat
        models = live.ifEmpty {
            // Fallback to the models.dev catalog keys, then to baked-in defaults.
            cat.keys.sorted().ifEmpty { Store.DEFAULT_MODELS }
        }
        if (live.isEmpty() && cat.isEmpty()) {
            modelsError = if (key.isBlank()) {
                when (provider) {
                    Provider.OPENROUTER -> "Add your OpenRouter key in Settings to list live models."
                    else -> "No key saved — showing built-in models. Add a key in Settings for the live list."
                }
            } else {
                "Could not reach the model list — showing cached models. Check your connection."
            }
        } else if (live.isEmpty()) {
            modelsError = "Live list unavailable — showing catalog models."
        }
    }

    /** Overlays privacy classifications onto a models.dev catalog. Never throws. */
    private fun withPrivacy(
        provider: Provider,
        cat: Map<String, ModelInfo>,
    ): Map<String, ModelInfo> {
        return runCatching {
            when (provider) {
                Provider.OPENROUTER -> {
                    val zdr = ApiClient.fetchOpenRouterZdrIds()
                    val training = ApiClient.fetchOpenRouterTrainingPrefixes()
                    if (zdr.isEmpty() && training.isEmpty()) return cat
                    cat.mapValues { (id, info) ->
                        info.copy(privacy = ApiClient.privacyForOpenRouter(id, zdr, training))
                    }
                }
                Provider.ZEN -> cat.mapValues { (id, info) ->
                    info.copy(privacy = ApiClient.privacyForZen(id))
                }
                Provider.GO -> cat.mapValues { (id, info) ->
                    info.copy(privacy = ApiClient.privacyForGo(id))
                }
            }
        }.getOrDefault(cat)
    }

    /** Effort values the current model actually supports; empty when unknown/not applicable. */
    fun effortsFor(id: String): List<String> = catalog[id]?.efforts ?: emptyList()

    fun currentEffortOrNull(): String? {
        val avail = effortsFor(model)
        return when {
            avail.isEmpty() -> null
            effort in avail -> effort
            else -> null
        }
    }
    fun putPendingImage(dataUrl: String?) { pendingImage = dataUrl }
    fun clearPendingImage() { pendingImage = null }

    private fun normalizeEffort() {
        val available = effortsFor(model)
        if (available.isEmpty() || effort in available) return
        effort = if ("medium" in available) "medium" else available.first()
        store.effort = effort
    }

    fun selectModel(id: String) {
        model = id
        store.model = id
        normalizeEffort()
    }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        store.favorites = favorites
    }

    fun setFreeOnly(enabled: Boolean) {
        showFreeOnly = enabled
        store.showFreeOnly = enabled
        val ordered = orderedModels()
        if (ordered.isNotEmpty() && model !in ordered) selectModel(ordered.first())
    }

    fun setZdrOnly(enabled: Boolean) {
        showZdrOnly = enabled
        store.showZdrOnly = enabled
        val ordered = orderedModels()
        if (ordered.isNotEmpty() && model !in ordered) selectModel(ordered.first())
    }

    fun changeProvider(p: Provider) {
        if (provider == p) return
        provider = p
        store.provider = p
        apiKey = store.loadApiKey(p)
        models = emptyList()
        catalog = emptyMap()
        modelsError = null
        refreshModels()
    }

    fun changeEffort(id: String) {
        effort = id
        store.effort = id
    }

    fun changeFontScale(scale: Float) {
        fontScale = scale
        store.fontScale = scale
    }

    fun saveApiKey(key: String) {
        saveApiKeyFor(provider, key)
    }

    fun saveApiKeyFor(p: Provider, key: String) {
        val trimmed = key.trim()
        store.saveApiKey(p, trimmed)
        if (p == provider) {
            apiKey = trimmed
            refreshModels()
        }
    }

    fun savedKeyFor(p: Provider): String = store.loadApiKey(p)

    fun newChat() {
        dropEphemeral()
        clearPendingImage()
        addSession(Session(Store.newId(), "New chat"))
    }

    fun newEphemeralChat() {
        dropEphemeral()
        clearPendingImage()
        addSession(Session(Store.newId(), "Ephemeral chat", ephemeral = true))
    }

    fun selectSession(id: String) {
        dropEphemeral()
        clearPendingImage()
        currentId = id
    }

    fun deleteSession(id: String) {
        sessions.removeAll { it.id == id }
        viewModelScope.launch(Dispatchers.IO) { store.deleteSession(id) }
        if (currentId == id) currentId = sessions.firstOrNull()?.id
    }

    fun send(text: String, imageDataUrl: String?) {
        val session = current?.takeIf { !busy && (text.isNotBlank() || imageDataUrl != null) } ?: return
        if (provider.needsApiKey && apiKey.isBlank()) {
            val updatedDenied = session.copy(
                messages = session.messages + ChatMessage("user", text.trim(), imageDataUrl) +
                    ChatMessage("assistant", "Error: Add your ${provider.label} API key in Settings first."),
                title = session.messages.takeIf { it.isEmpty() }?.let { text.take(48).ifBlank { "Photo" } } ?: session.title,
            )
            clearPendingImage()
            replace(updatedDenied)
            viewModelScope.launch(Dispatchers.IO) { store.saveSession(updatedDenied) }
            return
        }
        val userMessage = ChatMessage("user", text.trim(), imageDataUrl)
        val updated = session.copy(
            messages = session.messages + userMessage,
            title = session.messages.takeIf { it.isEmpty() }?.let { text.take(48).ifBlank { "Photo" } } ?: session.title,
        )
        clearPendingImage()
        replace(updated)

        busy = true
        streamingText = ""
        viewModelScope.launch {
            try {
                val sb = StringBuilder()
                val req = ApiClient.ChatRequest(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    effort = currentEffortOrNull(),
                    history = updated.messages,
                    catalog = catalog,
                    sessionId = updated.id,
                    zdrOnly = showZdrOnly,
                )
                ApiClient.streamResilient(req).collect { piece ->
                    sb.append(piece)
                    streamingText = sb.toString()
                }
                val finalText = sb.toString().ifBlank { "(empty response — try another model)" }
                finishStream(updated, ChatMessage("assistant", finalText))
            } catch (e: Exception) {
                finishStream(updated, ChatMessage("assistant", "Error: ${e.message ?: "request failed"}"))
            } finally {
                busy = false
                streamingText = null
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            store.clearAll()
            withContext(Dispatchers.Main) {
                sessions.clear()
                currentId = null
                apiKey = ""
                favorites = emptyList()
                models = emptyList()
                catalog = emptyMap()
                modelsError = null
                model = Store.DEFAULT_MODELS.first()
                provider = Provider.ZEN
                effort = "medium"
                fontScale = 1.0f
                showFreeOnly = false
                showZdrOnly = false
            }
        }
    }

    private fun addSession(session: Session) {
        sessions.add(session)
        currentId = session.id
    }

    private fun dropEphemeral() {
        sessions.removeAll { it.ephemeral }
        if (currentId != null && sessions.none { it.id == currentId }) currentId = null
    }

    private fun replace(session: Session) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session
    }

    private fun finishStream(base: Session, assistant: ChatMessage) {
        val done = base.copy(messages = base.messages + assistant)
        replace(done)
        if (!done.ephemeral) {
            viewModelScope.launch(Dispatchers.IO) { store.saveSession(done) }
        }
    }
}
