package com.slidemill.core

/**
 * One interface over any LLM key (port of app/llm.py): Anthropic natively with structured outputs,
 * everything else through the OpenAI-compatible chat API in JSON mode with one repair retry.
 */
class Llm(private val store: Store) {
    data class Preset(val label: String, val baseUrl: String, val defaultModel: String, val vision: Boolean = true)

    data class Config(val provider: String, val apiKey: String, val model: String, val baseUrl: String) {
        val vision: Boolean get() = (PRESETS[provider] ?: PRESETS.getValue("custom")).vision || provider == "custom"
    }

    companion object {
        // Default models are starting points; any model the provider serves can be set in Settings.
        val PRESETS: Map<String, Preset> = linkedMapOf(
            "anthropic" to Preset("Anthropic (Claude)", "https://api.anthropic.com", "claude-opus-5-5"),
            "openai" to Preset("OpenAI", "https://api.openai.com/v1", "gpt-5-mini"),
            "gemini" to Preset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
            "openrouter" to Preset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
            "groq" to Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", vision = false),
            "deepseek" to Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", vision = false),
            "xai" to Preset("xAI (Grok)", "https://api.x.ai/v1", "grok-4"),
            "mistral" to Preset("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest"),
            "together" to Preset("Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo", vision = false),
            "ollama" to Preset("Ollama (local)", "http://localhost:11434/v1", "llama3.1", vision = false),
            "custom" to Preset("Custom OpenAI-compatible", "", "", vision = false),
        )

        // Environment variable names that pin the provider regardless of key shape.
        val ENV_PROVIDER = mapOf(
            "ANTHROPIC_API_KEY" to "anthropic", "OPENAI_API_KEY" to "openai", "GEMINI_API_KEY" to "gemini",
            "GOOGLE_API_KEY" to "gemini", "OPENROUTER_API_KEY" to "openrouter", "GROQ_API_KEY" to "groq",
            "DEEPSEEK_API_KEY" to "deepseek", "XAI_API_KEY" to "xai", "MISTRAL_API_KEY" to "mistral", "TOGETHER_API_KEY" to "together",
        )

        const val ANTHROPIC_EFFORT = "medium"

        fun detectProvider(raw: String): String? {
            val key = raw.trim()
            return when {
                key.startsWith("sk-ant-") -> "anthropic"
                key.startsWith("sk-or-") -> "openrouter"
                key.startsWith("gsk_") -> "groq"
                key.startsWith("AIza") -> "gemini"
                key.startsWith("xai-") -> "xai"
                key.startsWith("sk-") -> "openai" // DeepSeek keys share this prefix; pick DeepSeek explicitly in Settings
                Regex("[0-9a-f]{64}").matches(key) -> "together"
                else -> null
            }
        }

        fun extractJson(raw: String): String {
            val text = raw.trim()
            Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL).find(text)?.let { return it.groupValues[1].trim() }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start != -1 && end > start) text.substring(start, end + 1) else text
        }

        fun schemaInstructions(schema: Schema): String =
            "Respond with a single JSON object and nothing else (no prose, no code fences). " +
                "It must validate against this JSON Schema:\n${Json.write(schema.toJson())}"
    }

    fun resolve(): Config? {
        val key = store.get("llm_api_key")
        var provider = store.get("llm_provider")
        if (provider.isEmpty()) provider = ENV_PROVIDER[store.envSource("llm_api_key") ?: ""] ?: detectProvider(key) ?: ""
        if (provider.isEmpty()) return null
        if (key.isEmpty() && provider !in setOf("ollama", "custom")) return null
        val preset = PRESETS[provider] ?: PRESETS.getValue("custom")
        val model = store.get("llm_model").ifEmpty { preset.defaultModel }
        val baseUrl = store.get("llm_base_url").ifEmpty { preset.baseUrl }.trimEnd('/')
        if (model.isEmpty() || baseUrl.isEmpty()) return null
        return Config(provider, key, model, baseUrl)
    }

    fun configured(): Boolean = resolve() != null

    /** Ask the configured model for JSON matching `schema`. `images` are http(s) or data: URLs. */
    fun completeJson(system: String, user: String, schema: Schema, images: List<String>? = null, cfg: Config? = null): Obj {
        val c = cfg ?: resolve()
            ?: throw LlmError("No AI key configured. Add one in Settings (any of: Anthropic, OpenAI, Gemini, OpenRouter, Groq, …).")
        val imgs = if (images.isNullOrEmpty() || !c.vision) null else images
        return if (c.provider == "anthropic") anthropic(c, system, user, schema, imgs) else openAiCompatible(c, system, user, schema, imgs)
    }

    private fun anthropic(cfg: Config, system: String, user: String, schema: Schema, images: List<String>?): Obj {
        val content = mutableListOf<Any?>()
        for (url in images.orEmpty()) {
            if (url.startsWith("data:")) {
                val media = url.substring(5).substringBefore(";base64,")
                val data = url.substringAfter(";base64,")
                content += obj("type" to "image", "source" to obj("type" to "base64", "media_type" to media, "data" to data))
            } else {
                content += obj("type" to "image", "source" to obj("type" to "url", "url" to url))
            }
        }
        content += obj("type" to "text", "text" to user)
        val outputConfig = obj("format" to obj("type" to "json_schema", "schema" to schema.toJson()))
        val m = cfg.model
        if (listOf("claude-opus-5", "claude-fable", "claude-sonnet-5", "claude-haiku-5", "claude-opus-4-8").any { m.startsWith(it) }) {
            outputConfig["effort"] = ANTHROPIC_EFFORT
        }
        val body = obj(
            "model" to m,
            "max_tokens" to 16000,
            "system" to system,
            "messages" to arr(obj("role" to "user", "content" to content)),
            "output_config" to outputConfig,
        )
        val headers = linkedMapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
        // Server-side refusal fallback is a Claude API feature for the current Claude models.
        if (listOf("claude-opus-5", "claude-fable", "claude-sonnet-5-5").any { m.startsWith(it) }) {
            body["fallbacks"] = "default"
            headers["anthropic-beta"] = "server-side-fallback-2026-07-01"
        }
        val resp = try {
            Net.postJson("${cfg.baseUrl}/v1/messages", body, headers, timeoutSec = 300)
        } catch (e: NetError) {
            throw LlmError("Could not reach the Anthropic API.")
        }
        when {
            resp.status == 401 -> throw LlmError("Anthropic rejected the API key.")
            resp.status == 429 -> throw LlmError("Anthropic rate limit hit; try again shortly.")
            resp.status >= 400 -> {
                val msg = runCatching { resp.obj().objAt("error").str("message") }.getOrNull()?.ifEmpty { null } ?: resp.text.take(300)
                throw LlmError("Anthropic API error ${resp.status}: $msg")
            }
        }
        val data = runCatching { resp.obj() }.getOrElse { throw LlmError("Unexpected response from Anthropic: ${resp.text.take(300)}") }
        if (data.str("stop_reason") == "refusal") throw LlmError("The model declined this request; adjust the topic.")
        val text = data.objs("content").filter { it.str("type") == "text" }.joinToString("") { it.str("text") }
        if (text.isBlank()) throw LlmError("The model response was cut off or empty.")
        return try {
            schema.validate(Json.parse(extractJson(text))) as Obj
        } catch (e: RuntimeException) {
            throw LlmError("The model response was cut off or empty.")
        }
    }

    private fun openAiCompatible(cfg: Config, system: String, user: String, schema: Schema, images: List<String>?): Obj {
        val userContent: Any = if (images != null) {
            arr(obj("type" to "text", "text" to user)).also { list ->
                images.forEach { list += obj("type" to "image_url", "image_url" to obj("url" to it)) }
            }
        } else user
        val messages = arr(
            obj("role" to "system", "content" to "$system\n\n${schemaInstructions(schema)}"),
            obj("role" to "user", "content" to userContent),
        )
        val headers = linkedMapOf<String, String>()
        if (cfg.apiKey.isNotEmpty()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        if (cfg.provider == "openrouter") {
            headers["HTTP-Referer"] = "https://github.com/slidemill"
            headers["X-Title"] = "Slidemill"
        }
        var lastError = ""
        repeat(2) {
            val body = obj("model" to cfg.model, "messages" to messages, "response_format" to obj("type" to "json_object"))
            var resp = post(cfg, body, headers)
            if (resp.status == 400 && "response_format" in resp.text) {
                body.remove("response_format") // some servers reject JSON mode; the schema prompt still applies
                resp = post(cfg, body, headers)
            }
            when {
                resp.status == 401 || resp.status == 403 -> throw LlmError("${cfg.provider} rejected the API key (${resp.status}).")
                resp.status == 429 -> throw LlmError("${cfg.provider} rate limit or quota exceeded.")
                resp.status >= 400 -> throw LlmError("${cfg.provider} error ${resp.status}: ${resp.text.take(400)}")
            }
            val text = try {
                val choice = resp.obj().objs("choices").first()
                choice.objAt("message").str("content")
            } catch (e: Exception) {
                throw LlmError("Unexpected response from ${cfg.provider}: ${resp.text.take(300)}")
            }
            try {
                return schema.validate(Json.parse(extractJson(text))) as Obj
            } catch (e: RuntimeException) {
                if (e !is SchemaError && e !is JsonError && e !is ClassCastException) throw e
                lastError = (e.message ?: "invalid JSON").take(1500)
                messages += obj("role" to "assistant", "content" to text)
                messages += obj(
                    "role" to "user",
                    "content" to "That JSON did not match the schema:\n$lastError\nReturn the corrected JSON object only.",
                )
            }
        }
        throw LlmError("Model did not return valid JSON: ${lastError.take(300)}")
    }

    private fun post(cfg: Config, body: Obj, headers: Map<String, String>): NetResponse = try {
        Net.postJson("${cfg.baseUrl}/chat/completions", body, headers, timeoutSec = 180)
    } catch (e: NetError) {
        throw LlmError("Could not reach ${cfg.provider}: ${e.message}")
    }

    fun test(cfg: Config? = null): String {
        val c = cfg ?: resolve() ?: throw LlmError("No AI key configured.")
        val result = completeJson("You are a health check.", "Return {\"ok\": true}.", Shapes.PING, cfg = c)
        if (!result.bool("ok")) throw LlmError("Model answered but returned ok=false.")
        return "${(PRESETS[c.provider] ?: PRESETS.getValue("custom")).label} · ${c.model}"
    }
}

class LlmError(message: String) : RuntimeException(message)
