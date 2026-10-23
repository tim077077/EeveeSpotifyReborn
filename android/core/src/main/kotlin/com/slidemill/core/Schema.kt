package com.slidemill.core

/**
 * JSON shapes the AI must return (the Python server's Pydantic models), as JSON Schema for the
 * prompt / structured outputs plus a validator for providers that only have a JSON mode.
 */
sealed class Schema(val description: String) {
    class Str(description: String = "", val enum: List<String>? = null) : Schema(description)
    class Bool(description: String = "") : Schema(description)
    class Array(val item: Schema, description: String = "") : Schema(description)
    class Object(val title: String, val props: LinkedHashMap<String, Schema>, description: String = "") : Schema(description)

    /** JSON Schema with every property required and no extra properties (structured-output friendly). */
    fun toJson(): Obj {
        val out: Obj = when (this) {
            is Str -> obj("type" to "string").also { o -> enum?.let { o["enum"] = it.toMutableList() } }
            is Bool -> obj("type" to "boolean")
            is Array -> obj("type" to "array", "items" to item.toJson())
            is Object -> obj(
                "type" to "object",
                "properties" to LinkedHashMap<String, Any?>().also { m -> props.forEach { (k, v) -> m[k] = v.toJson() } },
                "required" to props.keys.toMutableList(),
                "additionalProperties" to false,
            )
        }
        if (description.isNotEmpty()) out["description"] = description
        return out
    }

    /** Validate parsed JSON; returns it (extra keys dropped) or throws [SchemaError] listing every problem. */
    fun validate(value: Any?): Any? {
        val errors = mutableListOf<String>()
        val out = check(value, "", errors)
        if (errors.isNotEmpty()) throw SchemaError("${errors.size} validation error(s)\n" + errors.take(20).joinToString("\n"))
        return out
    }

    private fun check(value: Any?, path: String, errors: MutableList<String>): Any? {
        val where = path.ifEmpty { "(root)" }
        return when (this) {
            is Str -> {
                if (value !is String) { errors += "$where: Input should be a valid string"; return value }
                if (enum != null && value !in enum) errors += "$where: Input should be " + enum.joinToString(", ") { "'$it'" }
                value
            }
            is Bool -> {
                if (value !is Boolean) errors += "$where: Input should be a valid boolean"
                value
            }
            is Array -> {
                if (value !is List<*>) { errors += "$where: Input should be a valid list"; return value }
                value.mapIndexedTo(mutableListOf()) { i, x -> item.check(x, if (path.isEmpty()) "$i" else "$path.$i", errors) }
            }
            is Object -> {
                if (value !is Map<*, *>) { errors += "$where: Input should be a valid dictionary"; return value }
                val out = obj()
                for ((k, s) in props) {
                    val sub = if (path.isEmpty()) k else "$path.$k"
                    if (!value.containsKey(k)) { errors += "$sub: Field required"; continue }
                    out[k] = s.check(value[k], sub, errors)
                }
                out
            }
        }
    }

    companion object {
        fun obj(title: String, vararg props: Pair<String, Schema>) = Object(title, linkedMapOf(*props))
    }
}

class SchemaError(message: String) : RuntimeException(message)

object Shapes {
    val FONTS = listOf("tiktok", "bebas", "anton", "archivo", "cormorant", "cormorant_italic", "playfair_italic")
    val FONT_SIZES = listOf("extra_extra_small", "extra_small", "small", "medium", "large", "extra_large")
    val TEXT_STYLES = listOf("outline", "text", "background", "light_background")
    val TEXT_COLORS = listOf("white", "black", "yellow", "red", "pink", "orange", "green", "blue", "purple")
    val TEXT_WIDTHS = listOf("full", "100%", "80%", "50%")
    val TEXT_POSITIONS = listOf("top", "center", "bottom")
    val ASPECT_RATIOS = listOf("4:5", "9:16", "1:1", "16:9")
    val IMAGE_LAYOUTS = listOf("single", "1:2", "1:3", "2:1", "2:2")
    val ROLES = listOf("hook", "body", "cta")
    val PRIVACY = listOf("PUBLIC_TO_EVERYONE", "MUTUAL_FOLLOW_FRIENDS", "FOLLOWER_OF_CREATOR", "SELF_ONLY")

    val PLAN_TEXT_ITEM = Schema.obj(
        "PlanTextItem",
        "text" to Schema.Str("Exact on-slide text. Short lines; no hashtags; no emojis unless the style asks."),
        "font" to Schema.Str(enum = FONTS),
        "font_size" to Schema.Str(enum = FONT_SIZES),
        "text_style" to Schema.Str(enum = TEXT_STYLES),
        "text_color" to Schema.Str(enum = TEXT_COLORS),
    )

    val PLAN_SLIDE = Schema.obj(
        "PlanSlide",
        "role" to Schema.Str(enum = ROLES),
        "text_items" to Schema.Array(PLAN_TEXT_ITEM),
        "image_query" to Schema.Str("3-6 word stock-photo search phrase for this slide's background, aesthetic and specific."),
        "image_prompt" to Schema.Str(
            "One-sentence description of this slide's background photo for an AI image generator: subject, setting, " +
                "lighting, framing. Never include text, captions, letters or logos in the image."
        ),
    )

    val SLIDESHOW_PLAN = Schema.obj(
        "SlideshowPlan",
        "title" to Schema.Str("Internal title for the dashboard, under 60 characters."),
        "caption" to Schema.Str("TikTok post title, at most 89 characters."),
        "description" to Schema.Str("TikTok post description: one or two sentences plus 3-5 relevant hashtags."),
        "text_position" to Schema.Str(enum = TEXT_POSITIONS),
        "slides" to Schema.Array(PLAN_SLIDE),
    )

    val CAMPAIGN_PLAN = Schema.obj(
        "CampaignPlan",
        "name" to Schema.Str(),
        "format_summary" to Schema.Str(),
        "style" to Schema.Str(),
        "visual_style" to Schema.Str(),
        "aspect_ratio" to Schema.Str(enum = ASPECT_RATIOS),
        "hooks" to Schema.Array(Schema.Str()),
        "language" to Schema.Str(),
    )

    val SLIDE_TEXT_READ = Schema.obj(
        "SlideTextRead",
        "slides" to Schema.Array(Schema.Str(), "The visible overlay text of each image, in order. Empty string if none."),
    )

    val PROFILE_CLASSIFICATION = Schema.obj(
        "ProfileClassification",
        "niche" to Schema.Str("One or two lowercase words, e.g. fitness, skincare, personal finance, studying, relationships."),
        "product_medium" to Schema.Str(enum = listOf("app", "physical product", "digital product", "service", "creator", "none")),
    )

    val PING = Schema.obj("Ping", "ok" to Schema.Bool())
}
