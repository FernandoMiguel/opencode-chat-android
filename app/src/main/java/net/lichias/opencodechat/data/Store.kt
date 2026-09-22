package net.lichias.opencodechat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Store(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("opencode_chat", Context.MODE_PRIVATE)
    private val sessionsDir = File(appContext.filesDir, "sessions").apply { mkdirs() }

    var provider: Provider
        get() {
            // Migrate old int-ordinal storage; fall back to name storage.
            val name = prefs.getString(KEY_PROVIDER_NAME, null)
            if (name != null) return runCatching { Provider.valueOf(name) }.getOrDefault(Provider.ZEN)
            val ordinal = prefs.getInt(KEY_PROVIDER, Provider.ZEN.ordinal)
            return Provider.entries.getOrElse(ordinal) { Provider.ZEN }
        }
        set(value) = prefs.edit()
            .putInt(KEY_PROVIDER, value.ordinal)
            .putString(KEY_PROVIDER_NAME, value.name)
            .apply()

    var effort: String
        get() = prefs.getString(KEY_EFFORT, "medium") ?: "medium"
        set(value) = prefs.edit().putString(KEY_EFFORT, value).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODELS.first()) ?: DEFAULT_MODELS.first()
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var showFreeOnly: Boolean
        get() = prefs.getBoolean(KEY_FREE_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_FREE_ONLY, value).apply()

    var showZdrOnly: Boolean
        get() = prefs.getBoolean(KEY_ZDR_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_ZDR_ONLY, value).apply()

    var favorites: List<String>
        get() {
            val arr = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
            return runCatching {
                val json = JSONArray(arr)
                (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotEmpty) }
            }.getOrDefault(emptyList())
        }
        set(value) = prefs.edit()
            .putString(KEY_FAVORITES, JSONArray(value).toString())
            .apply()

    /** Stable install id used for the x-opencode-session / User-Agent headers (Go wants session affinity). */
    val installId: String
        get() {
            var id = prefs.getString(KEY_INSTALL_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_INSTALL_ID, id).apply()
            }
            return id!!
        }

    // ---- Per-provider API keys (encrypted) ----

    fun loadApiKey(provider: Provider): String =
        KeyVault.loadFor(appContext, KeyVault.keyIdForProvider(provider.name))

    fun saveApiKey(provider: Provider, key: String) =
        KeyVault.saveFor(appContext, KeyVault.keyIdForProvider(provider.name), key)

    /** Legacy single-key accessors (Zen). Kept for migration. */
    fun loadApiKey(): String = loadApiKey(Provider.ZEN)

    fun saveApiKey(key: String) = saveApiKey(Provider.ZEN, key)

    fun saveSession(session: Session) {
        if (session.ephemeral || session.messages.isEmpty()) return
        val json = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("messages", JSONArray().apply {
                session.messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("text", m.text)
                        m.imageDataUrl?.let { put("image", it) }
                    })
                }
            })
        }
        File(sessionsDir, session.id + ".json").writeBytes(KeyVault.encryptBytes(appContext, json.toString().toByteArray(Charsets.UTF_8)))
    }

    fun loadSessions(): List<Session> =
        sessionsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(String(KeyVault.decryptBytes(appContext, file.readBytes()), Charsets.UTF_8))
                    val msgs = json.getJSONArray("messages")
                    Session(
                        id = json.getString("id"),
                        title = json.optString("title").ifEmpty { "Chat" },
                        messages = (0 until msgs.length()).map { i ->
                            val m = msgs.getJSONObject(i)
                            ChatMessage(
                                role = m.optString("role", "user"),
                                text = m.optString("text"),
                                imageDataUrl = m.optString("image").takeIf { it.isNotEmpty() },
                            )
                        },
                    )
                }.getOrNull()
            }
            ?.sortedBy { it.id }
            ?: emptyList()

    fun deleteSession(id: String) {
        File(sessionsDir, "$id.json").delete()
    }

    fun clearAll() {
        sessionsDir.listFiles()?.forEach { it.delete() }
        KeyVault.clear(appContext)
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_PROVIDER_NAME = "provider_name"
        private const val KEY_EFFORT = "effort"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_MODEL = "model"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FREE_ONLY = "free_only"
        private const val KEY_ZDR_ONLY = "zdr_only"
        private const val KEY_INSTALL_ID = "install_id"

        val DEFAULT_MODELS = listOf(
            "gpt-5-nano",
            "grok-4.6",
            "kimi-k3",
            "glm-5.2",
            "deepseek-v4-flash-free",
            "x-preview-f-free",
            "laguna-s-2.1-free",
        )

        fun newId(): String = UUID.randomUUID().toString()
    }
}
