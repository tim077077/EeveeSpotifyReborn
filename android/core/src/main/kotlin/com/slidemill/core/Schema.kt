package com.slidemill.core

/**
 * JSON shapes the AI must return (the Python server's Pydantic models), as JSON Schema for the
 * prompt / structured outputs plus a validator for providers that only have a JSON mode.
 */
sealed class Schema(val description: String) {
    class Str(description: String = "", val enum: List<String>? = null) : Schema(description)
    class Bool(description: String = "") : Schema(description)
    class Integer(description: String = "") : Schema(description)
    class Array(val item: Schema, description: String = "") : Schema(description)
    /** [optional] properties may be left out (the caller fills defaults); the rest are required. */
    class Object(val title: String, val props: LinkedHashMap<String, Schema>, description: String = "", val optional: Set<String> = emptySet()) : Schema(description)

    /** JSON Schema with every property required and no extra properties (structured-output friendly). */
    fun toJson(): Obj {
        val out: Obj = when (this) {
            is Str -> obj("type" to "string").also { o -> enum?.let { o["enum"] = it.toMutableList() } }
            is Bool -> obj("type" to "boolean")
            is Integer -> obj("type" to "integer")
            is Array -> obj("type" to "array", "items" to item.toJson())
            is Object -> obj(
                "type" to "object",
                "properties" to LinkedHashMap<String, Any?>().also { m -> props.forEach { (k, v) -> m[k] = v.toJson() } },
                "required" to props.keys.filter { it !in optional }.toMutableList<Any?>(),
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
            is Integer -> when {
                // Lenient like Pydantic's lax mode: 72, 72.0 and "72" are all 72.
                value is Number && value.toDouble() == Math.floor(value.toDouble()) -> value.toLong()
                value is String && value.trim().toLongOrNull() != null -> value.trim().toLong()
                else -> { errors += "$where: Input should be a valid integer"; value }
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
                    if (!value.containsKey(k)) { if (k !in optional) errors += "$sub: Field required"; continue }
                    out[k] = s.check(value[k], sub, errors)
                }
                out
            }
        }
    }

    companion object {
        fun obj(title: String, vararg props: Pair<String, Schema>) = Object(title, linkedMapOf(*props))
        fun obj(title: String, optional: Set<String>, vararg props: Pair<String, Schema>) = Object(title, linkedMapOf(*props), optional = optional)
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

    val PLAN_TEXT_ITEM = Schema.obj(
        "PlanTextItem", setOf("font", "font_size", "text_style", "text_color"),
        "text" to Schema.Str("Exact on-slide text, short lines, no hashtags. Style fields optional (default tiktok/medium/outline/white)."),
        "font" to Schema.Str(enum = FONTS),
        "font_size" to Schema.Str(enum = FONT_SIZES),
        "text_style" to Schema.Str(enum = TEXT_STYLES),
        "text_color" to Schema.Str(enum = TEXT_COLORS),
    )

    val PLAN_SLIDE = Schema.obj(
        "PlanSlide", setOf("role"),
        "role" to Schema.Str(enum = ROLES),
        "text_items" to Schema.Array(PLAN_TEXT_ITEM),
        "image_query" to Schema.Str("3-6 word stock-photo search for the background."),
        "image_prompt" to Schema.Str("One sentence for an AI background photo: subject, setting, light. No text or logos in it."),
    )

    val SLIDE_EDIT = Schema.obj(
        "SlideEdit", setOf("texts", "image_query", "image_prompt", "new_background", "delete", "insert_after"),
        "slide" to Schema.Integer("Number before this call; insert_after: 0 = first."),
        "texts" to Schema.Array(Schema.Str(), "New text per text item (styles kept)."),
        "image_query" to Schema.Str(),
        "image_prompt" to Schema.Str(),
        "new_background" to Schema.Bool(),
        "delete" to Schema.Bool(),
        "insert_after" to Schema.Bool("Add a new slide after slide."),
    )

    val CLAIM_CHECK = Schema.obj(
        "ClaimCheck",
        "claim" to Schema.Str(),
        "verdict" to Schema.Str(enum = listOf("true", "unsure", "false")),
    )

    val SLIDESHOW_PLAN = Schema.obj(
        "SlideshowPlan", setOf("text_position"),
        "title" to Schema.Str("Internal name, under 60 chars."),
        "caption" to Schema.Str("TikTok post title, max 89 chars."),
        "description" to Schema.Str("Post caption: 1-2 sentences + 3-5 hashtags."),
        "text_position" to Schema.Str(enum = TEXT_POSITIONS),
        "slides" to Schema.Array(PLAN_SLIDE),
    )

}
