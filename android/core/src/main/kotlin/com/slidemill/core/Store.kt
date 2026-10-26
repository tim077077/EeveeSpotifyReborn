package com.slidemill.core

/** Dashboard-editable settings (port of app/store.py). Secrets are encrypted by the platform. */
class Store(private val db: Db, private val platform: Platform) {
    companion object {
        // setting key -> environment variables consulted when the key is unset
        val ENV_FALLBACKS: Map<String, List<String>> = linkedMapOf(
            "llm_provider" to listOf("LLM_PROVIDER"),
            "llm_api_key" to listOf(
                "LLM_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY",
                "OPENROUTER_API_KEY", "GROQ_API_KEY", "DEEPSEEK_API_KEY", "XAI_API_KEY", "MISTRAL_API_KEY", "TOGETHER_API_KEY",
            ),
            "llm_model" to listOf("LLM_MODEL", "ANTHROPIC_MODEL"),
            "llm_base_url" to listOf("LLM_BASE_URL"),
            "image_provider" to listOf("IMAGE_PROVIDER"),
            "image_api_key" to listOf("IMAGE_API_KEY"),
            "image_model" to listOf("IMAGE_MODEL"),
            "pexels_api_key" to listOf("PEXELS_API_KEY"),
            "unsplash_access_key" to listOf("UNSPLASH_ACCESS_KEY"),
            "apify_token" to listOf("APIFY_TOKEN"),
            // Posting from the phone / browser
            "browser_posts_per_day" to listOf("BROWSER_POSTS_PER_DAY"),
            "browser_min_gap_minutes" to listOf("BROWSER_MIN_GAP_MINUTES"),
            "browser_username" to listOf("BROWSER_TIKTOK_USERNAME"),
        )
        val SECRET_KEYS = setOf("llm_api_key", "image_api_key", "pexels_api_key", "unsplash_access_key", "apify_token")

        fun mask(value: String): String = when {
            value.isEmpty() -> ""
            value.length > 12 -> value.take(4) + "…" + value.takeLast(4)
            else -> "••••"
        }
    }

    private fun dbValue(key: String): String {
        val stored = db.tx { get("settings", key)?.str("value") } ?: return ""
        if (stored.isEmpty()) return ""
        return runCatching { platform.decryptSecret(stored) }.getOrDefault("")
    }

    fun get(key: String, default: String = ""): String {
        dbValue(key).takeIf { it.isNotEmpty() }?.let { return it }
        for (env in ENV_FALLBACKS[key].orEmpty()) platform.env(env)?.takeIf { it.isNotEmpty() }?.let { return it }
        return default
    }

    fun envSource(key: String): String? = ENV_FALLBACKS[key].orEmpty().firstOrNull { !platform.env(it).isNullOrEmpty() }

    /** Throws IllegalArgumentException("unknown setting ...") for keys that don't exist. */
    fun setMany(values: Map<String, String>) {
        for (key in values.keys) require(key in ENV_FALLBACKS) { "unknown setting '$key'" }
        db.tx {
            for ((key, raw) in values) {
                val value = raw.trim()
                insert("settings", obj("id" to key, "value" to if (value.isEmpty()) "" else platform.encryptSecret(value)))
            }
        }
    }

    fun publicView(): Obj {
        val out = obj()
        for (key in ENV_FALLBACKS.keys) {
            val value = get(key)
            val inDb = dbValue(key).isNotEmpty()
            out[key] = obj(
                "value" to if (key in SECRET_KEYS) mask(value) else value,
                "set" to value.isNotEmpty(),
                "from_env" to if (value.isNotEmpty() && !inDb) envSource(key) else null,
            )
        }
        return out
    }
}
