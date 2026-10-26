package com.slidemill.core

/**
 * Request/document shapes with the same defaults and checks as the Python server's Pydantic
 * models (app/schemas.py). `Model.of(input)` returns a complete, validated copy or throws
 * [ValidationError] (served as a 422).
 */
class ValidationError(message: String) : RuntimeException(message)

class Field(val name: String, val default: () -> Any?, val check: (Any?, String) -> Any?, val required: Boolean = false)

class Model(private val fields: List<Field>) {
    val names: Set<String> get() = fields.map { it.name }.toSet()

    /** Full object: missing fields get defaults; unknown keys are dropped. */
    fun of(input: Any?, path: String = ""): Obj {
        val src = input as? Map<*, *> ?: if (input == null) emptyMap<String, Any?>() else throw ValidationError("${path.ifEmpty { "body" }}: expected an object")
        val out = obj()
        for (f in fields) {
            val where = if (path.isEmpty()) f.name else "$path.${f.name}"
            if (!src.containsKey(f.name)) {
                if (f.required) throw ValidationError("$where: field required")
                out[f.name] = f.default()
            } else {
                out[f.name] = f.check(src[f.name], where)
            }
        }
        return out
    }

    /** Only the fields present in the input (PATCH bodies), each validated. */
    fun partial(input: Any?): Obj {
        val src = input as? Map<*, *> ?: throw ValidationError("body: expected an object")
        val out = obj()
        for (f in fields) if (src.containsKey(f.name)) {
            out[f.name] = f.check(src[f.name], f.name)
        }
        return out
    }
}

object V {
    fun str(enum: List<String>? = null): (Any?, String) -> Any? = { v, where ->
        val s = when (v) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> throw ValidationError("$where: Input should be a valid string")
        }
        if (enum != null && s !in enum) throw ValidationError("$where: Input should be " + enum.joinToString(", ") { "'$it'" })
        s
    }

    fun nullableStr(enum: List<String>? = null): (Any?, String) -> Any? = { v, where -> if (v == null) null else str(enum)(v, where) }

    val bool: (Any?, String) -> Any? = { v, where ->
        when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> when (v.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off", "" -> false
                else -> throw ValidationError("$where: Input should be a valid boolean")
            }
            else -> throw ValidationError("$where: Input should be a valid boolean")
        }
    }

    val nullableBool: (Any?, String) -> Any? = { v, where -> if (v == null) null else bool(v, where) }

    fun int(min: Long? = null, max: Long? = null): (Any?, String) -> Any? = { v, where ->
        val n = when (v) {
            is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong() else throw ValidationError("$where: Input should be a valid integer")
            is String -> v.trim().toLongOrNull() ?: throw ValidationError("$where: Input should be a valid integer")
            else -> throw ValidationError("$where: Input should be a valid integer")
        }
        if (min != null && n < min) throw ValidationError("$where: Input should be greater than or equal to $min")
        if (max != null && n > max) throw ValidationError("$where: Input should be less than or equal to $max")
        n
    }

    val number: (Any?, String) -> Any? = { v, where ->
        when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: throw ValidationError("$where: Input should be a valid number")
            else -> throw ValidationError("$where: Input should be a valid number")
        }
    }

    fun strList(maxLen: Int? = null): (Any?, String) -> Any? = { v, where ->
        val list = v as? List<*> ?: throw ValidationError("$where: Input should be a valid list")
        if (maxLen != null && list.size > maxLen) throw ValidationError("$where: List should have at most $maxLen items")
        list.mapIndexedTo(mutableListOf<Any?>()) { i, x -> str()(x, "$where.$i") }
    }

    fun models(model: () -> Model, maxLen: Int? = null): (Any?, String) -> Any? = { v, where ->
        val list = v as? List<*> ?: throw ValidationError("$where: Input should be a valid list")
        if (maxLen != null && list.size > maxLen) throw ValidationError("$where: List should have at most $maxLen items")
        list.mapIndexedTo(mutableListOf<Any?>()) { i, x -> model().of(x, "$where.$i") }
    }

    fun model(model: () -> Model): (Any?, String) -> Any? = { v, where -> model().of(v, where) }

    val any: (Any?, String) -> Any? = { v, _ -> v }
}

private fun f(name: String, default: Any?, check: (Any?, String) -> Any?) = Field(name, { deepCopy(default) }, check)
private fun req(name: String, check: (Any?, String) -> Any?) = Field(name, { null }, check, required = true)

object Models {
    val TEXT_ITEM = Model(listOf(
        req("text", V.str()),
        f("font", "tiktok", V.str(Shapes.FONTS)),
        f("font_size", "medium", V.str()), // named size, or raw "48px"
        f("text_style", "outline", V.str(Shapes.TEXT_STYLES)),
        f("text_color", "white", V.str(Shapes.TEXT_COLORS)),
        f("text_width", "80%", V.str(Shapes.TEXT_WIDTHS)),
        f("text_align", "center", V.str(listOf("left", "center", "right"))),
    ))

    val SLIDE = Model(listOf(
        f("role", "body", V.str(Shapes.ROLES)),
        // Image references are media-relative paths ("library/abc.jpg") or http(s) URLs.
        f("image", null, V.nullableStr()),
        f("images", mutableListOf<Any?>(), V.strList()),
        f("image_layout", "single", V.str(Shapes.IMAGE_LAYOUTS)),
        f("text_items", mutableListOf<Any?>(), V.models({ TEXT_ITEM })),
        f("text_position", null, V.nullableStr(Shapes.TEXT_POSITIONS)),
        f("image_query", "", V.str()),
        f("image_prompt", "", V.str()),
    ))

    val SPEC = Model(listOf(
        f("title", "Untitled slideshow", V.str()),
        f("aspect_ratio", "4:5", V.str(Shapes.ASPECT_RATIOS)),
        f("text_position", "center", V.str(Shapes.TEXT_POSITIONS)),
        f("overlay_body", false, V.bool),
        f("overlay_hook", false, V.bool),
        f("overlay_cta", false, V.bool),
        f("background_opacity", 20L, V.int(0, 100)),
        f("keep_original_aspect_ratio", false, V.bool),
        f("slides", mutableListOf<Any?>(), V.models({ SLIDE }, maxLen = 20)),
    ))
}
