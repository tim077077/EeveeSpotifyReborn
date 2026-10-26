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

    val POST_SETTINGS = Model(listOf(
        f("caption_mode", "prompt", V.str(listOf("prompt", "static"))),
        f("caption_static", "", V.str()),
        f("description_mode", "prompt", V.str(listOf("prompt", "static"))),
        f("description_static", "", V.str()),
        f("auto_post", true, V.bool),
        // BROWSER = posted from a logged-in TikTok web session (the phone's TikTok tab or the extension).
        f("post_mode", "MEDIA_UPLOAD", V.str(listOf("DIRECT_POST", "MEDIA_UPLOAD", "BROWSER"))),
        // publish right away, or "draft": on the phone, hand it to the TikTok app to finish.
        f("browser_action", "publish", V.str(listOf("publish", "draft"))),
        f("visibility", "PUBLIC_TO_EVERYONE", V.str(Shapes.PRIVACY)),
        f("auto_music", true, V.bool),
        f("allow_comments", true, V.bool),
        f("disclose_ai_content", true, V.bool),
        f("brand_organic", false, V.bool),
        f("branded_content", false, V.bool),
        f("consented", false, V.bool),
    ))

    val IMAGE_SETTINGS = Model(listOf(
        f("source", "auto", V.str(listOf("auto", "stock", "ai", "collection"))),
        f("visual_style", "", V.str()),
        f("collection_id", null, V.nullableStr()),
        f("hook_collection_id", null, V.nullableStr()),
        f("cta_collection_id", null, V.nullableStr()),
        f("aspect_ratio", "4:5", V.str(Shapes.ASPECT_RATIOS)),
        f("overlay_body", false, V.bool),
        f("overlay_hook", false, V.bool),
        f("overlay_cta", false, V.bool),
        f("background_opacity", 30L, V.int(0, 100)),
        f("keep_original_aspect_ratio", false, V.bool),
        f("text_on_first_slide_only", false, V.bool),
        f("cta_slide", false, V.bool),
    ))

    val AUTOMATION_IN = Model(listOf(
        f("title", "Untitled automation", V.str()),
        f("tiktok_account_id", null, V.nullableStr()),
        f("product_id", null, V.nullableStr()),
        f("hooks", mutableListOf<Any?>(), V.strList()),
        f("style", "", V.str()),
        f("language", "English", V.str()),
        Field("post_settings", { POST_SETTINGS.of(null) }, V.model { POST_SETTINGS }),
        Field("image_settings", { IMAGE_SETTINGS.of(null) }, V.model { IMAGE_SETTINGS }),
        f("schedule", mutableListOf<Any?>(), V.strList()),
        f("timezone", "", V.str()),
        f("target_country", "", V.str()),
        // Pre-test each slideshow; below this predicted score, write one more version and keep the better. 0 = off.
        f("min_viral_score", 0L, V.int(0, 100)),
    ))

    val AUTOMATION_PATCH = Model(listOf(
        f("title", null, V.nullableStr()),
        f("tiktok_account_id", null, V.nullableStr()),
        f("product_id", null, V.nullableStr()),
        f("hooks", null, { v, w -> if (v == null) null else V.strList()(v, w) }),
        f("style", null, V.nullableStr()),
        f("language", null, V.nullableStr()),
        f("post_settings", null, { v, w -> if (v == null) null else POST_SETTINGS.of(v, w) }),
        f("image_settings", null, { v, w -> if (v == null) null else IMAGE_SETTINGS.of(v, w) }),
        f("schedule", null, { v, w -> if (v == null) null else V.strList()(v, w) }),
        f("timezone", null, V.nullableStr()),
        f("target_country", null, V.nullableStr()),
        f("paused", null, V.nullableBool),
        f("favorite", null, V.nullableBool),
        f("min_viral_score", null, { v, w -> if (v == null) null else V.int(0, 100)(v, w) }),
    ))

    val GENERATE = Model(listOf(
        req("prompt", V.str()),
        f("image_source", null, V.nullableStr(listOf("auto", "stock", "ai"))),
        f("automation_id", null, V.nullableStr()),
        f("images", mutableListOf<Any?>(), V.strList()),
        f("aspect_ratio", null, V.nullableStr(Shapes.ASPECT_RATIOS)),
        f("language", null, V.nullableStr()),
    ))

    val STUDIO = Model(listOf(
        req("brief", V.str()),
        f("count", 3L, V.int(1, 10)),
        f("image_source", "auto", V.str(listOf("auto", "stock", "ai"))),
        f("aspect_ratio", null, V.nullableStr(Shapes.ASPECT_RATIOS)),
        f("language", null, V.nullableStr()),
        f("product_id", null, V.nullableStr()),
        f("save_as_automation", false, V.bool),
        f("tiktok_account_id", null, V.nullableStr()),
        f("schedule", mutableListOf<Any?>(), V.strList()),
        f("target_country", null, V.nullableStr()),
        f("posts_per_day", 2L, V.int(1, 6)),
        f("auto_publish", false, V.bool),
        f("privacy_level", null, V.nullableStr(Shapes.PRIVACY)),
        f("consent", false, V.bool),
        f("via_browser", false, V.bool),
        f("browser_action", "publish", V.str(listOf("publish", "draft"))),
    ))

    val REMIX = Model(listOf(
        f("count", 1L, V.int(1, 5)),
        f("topic", null, V.nullableStr()),
        f("image_source", "auto", V.str(listOf("auto", "stock", "ai"))),
        f("aspect_ratio", null, V.nullableStr(Shapes.ASPECT_RATIOS)),
    ))

    val LIBRARY_IMPORT = Model(listOf(req("items", V.strList()), f("max_posts", 30L, V.int(1, 200))))
    val LIBRARY_DISCOVER = Model(listOf(req("query", V.str()), f("max_posts", 50L, V.int(1, 300))))

    val APPLY_TIMING = Model(listOf(
        req("country", V.str()),
        f("posts_per_day", 3L, V.int(1, 6)),
        f("auto_publish", true, V.bool),
        f("privacy_level", null, V.nullableStr(Shapes.PRIVACY)),
        f("consent", false, V.bool),
        f("allow_comments", false, V.bool),
        f("brand_organic", false, V.bool),
        f("branded_content", false, V.bool),
        f("via_browser", false, V.bool),
        f("browser_action", "publish", V.str(listOf("publish", "draft"))),
    ))

    val EXTENSION_RESULT = Model(listOf(
        req("ok", V.bool),
        f("item_id", "", V.str()),
        f("username", "", V.str()),
        f("error", "", V.str()),
        f("unsupported", false, V.bool),
    ))

    val RUN = Model(listOf(f("hook", null, V.nullableStr()), f("mode", "export", V.str(listOf("export", "draft_only")))))

    val PUBLISH = Model(listOf(
        f("tiktok_account_id", null, V.nullableStr()),
        f("post_mode", null, V.nullableStr(listOf("DIRECT_POST", "MEDIA_UPLOAD", "BROWSER"))),
        f("caption", null, V.nullableStr()),
        f("description", null, V.nullableStr()),
        f("browser_action", "publish", V.str(listOf("publish", "draft"))),
        f("sound", true, V.bool),
        f("privacy_level", null, V.nullableStr(Shapes.PRIVACY)),
        f("allow_comments", null, V.nullableBool),
        f("brand_organic", false, V.bool),
        f("branded_content", false, V.bool),
        f("consent", false, V.bool),
    ))

    val PRODUCT = Model(listOf(req("name", V.str()), f("description", "", V.str()), f("url", "", V.str())))
    val COLLECTION = Model(listOf(req("name", V.str())))
    val IMPORT_IMAGES = Model(listOf(req("query", V.str()), f("count", 12L, V.int(1, 40))))
}
