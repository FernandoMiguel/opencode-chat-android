package net.lichias.opencodechat.data

enum class ApiKind {
    CHAT,
    RESPONSES,
    MESSAGES,
    GEMINI,
}

enum class Provider(
    val label: String,
    val baseUrl: String,
    val catalogKey: String,
    val needsApiKey: Boolean,
) {
    ZEN("OpenCode Zen", "https://opencode.ai/zen/v1", "opencode", needsApiKey = true),
    GO("OpenCode Go", "https://opencode.ai/zen/go/v1", "opencode-go", needsApiKey = true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter", needsApiKey = true);
}

data class Effort(val id: String) {
    val label: String
        get() = when (id) {
            "xhigh" -> "X-High"
            else -> id.replaceFirstChar { it.uppercase() }
        }
}

data class ChatMessage(
    val role: String,
    val text: String,
    val imageDataUrl: String? = null,
)

data class Session(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val ephemeral: Boolean = false,
)

data class ModelInfo(
    val id: String,
    /** Reasoning effort values this model accepts, e.g. [low, high, max]. Empty = not exposed. */
    val efforts: List<String> = emptyList(),
    val apiKind: ApiKind = ApiKind.CHAT,
    /** True when the model is free (zero cost in models.dev, or -free/:free id heuristic). */
    val isFree: Boolean = false,
    /** Data-privacy classification (ZDR availability / training on prompts). */
    val privacy: Privacy = Privacy.UNKNOWN,
)

/**
 * Per-model data-privacy classification shown as a shield badge in the picker.
 *
 * - [ZDR]: at least one serving endpoint guarantees Zero Data Retention
 *   (no storage, hence no training). OpenRouter: present in
 *   `GET /api/v1/endpoints/zdr`; Zen/Go: paid models per the docs privacy tables.
 * - [TRAINS]: the provider may train on prompts (OpenRouter providers with
 *   `dataPolicy.training == true`; Zen/Go free/contributor models listed as
 *   collecting data to improve the model).
 * - [UNKNOWN]: no privacy signal available.
 */
enum class Privacy {
    UNKNOWN,
    ZDR,
    TRAINS,
}
