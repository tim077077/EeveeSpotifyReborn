package com.slidemill.core

/**
 * Minimal JSON for the engine: objects are MutableMap<String, Any?>, arrays MutableList<Any?>,
 * numbers Long (integers) or Double. Identical on the JVM and on Android (no org.json quirks).
 */
typealias Obj = MutableMap<String, Any?>
typealias Arr = MutableList<Any?>

fun obj(vararg pairs: Pair<String, Any?>): Obj = linkedMapOf(*pairs)
fun arr(vararg items: Any?): Arr = mutableListOf(*items)

class JsonError(message: String) : RuntimeException(message)

object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonError("trailing data at ${p.i}")
        return v
    }

    fun parseObj(text: String): Obj = parse(text) as? Obj ?: throw JsonError("expected a JSON object")

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> if (v.isFinite()) {
                if (v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()).append(".0") else sb.append(v)
            } else sb.append("null")
            is Float -> write(sb, v.toDouble())
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    fun quote(s: String): String = StringBuilder().also { quote(it, s) }.toString()

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '<' -> sb.append("\\u003c") // safe to embed in HTML <script>
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonError("unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw JsonError("unexpected '$c' at $i")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonError("bad literal at $i")
            i += word.length
            return v
        }

        fun objectValue(): Obj {
            val out = linkedMapOf<String, Any?>()
            i++
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                if (peek() != '"') throw JsonError("expected key at $i")
                val k = string()
                ws()
                if (peek() != ':') throw JsonError("expected ':' at $i")
                i++
                ws()
                out[k] = value()
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw JsonError("expected ',' or '}' at $i")
                }
            }
        }

        fun arrayValue(): Arr {
            val out = mutableListOf<Any?>()
            i++
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw JsonError("expected ',' or ']' at $i")
                }
            }
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonError("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonError("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isDouble = false
            if (peek() == '.') { isDouble = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (peek() == 'e' || peek() == 'E') {
                isDouble = true
                i++
                if (peek() == '+' || peek() == '-') i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            if (text == "-" || text.isEmpty()) throw JsonError("bad number at $start")
            if (!isDouble) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }
    }
}

// --- typed access helpers -----------------------------------------------------------------

fun Map<String, Any?>.str(key: String, default: String = ""): String = when (val v = this[key]) {
    null -> default
    is String -> v
    is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    else -> v.toString()
}

fun Map<String, Any?>.strOrNull(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

fun Map<String, Any?>.long(key: String, default: Long = 0): Long = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.trim().toDoubleOrNull()?.toLong() ?: default
    is Boolean -> if (v) 1 else 0
    else -> default
}

fun Map<String, Any?>.int(key: String, default: Int = 0): Int = long(key, default.toLong()).toInt()

fun Map<String, Any?>.dbl(key: String, default: Double = 0.0): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: default
    else -> default
}

fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.lowercase() in setOf("1", "true", "yes", "on")
    else -> default
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objOrNull(key: String): Obj? = this[key] as? Obj

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objAt(key: String): Obj = (this[key] as? Map<String, Any?>)?.toMutableMap() ?: linkedMapOf()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): Arr = (this[key] as? List<Any?>)?.toMutableList() ?: mutableListOf()

fun Map<String, Any?>.strings(key: String): MutableList<String> =
    list(key).mapNotNull { it as? String }.toMutableList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objs(key: String): MutableList<Obj> =
    list(key).mapNotNull { (it as? Map<String, Any?>)?.toMutableMap() }.toMutableList()

/** Deep copy, so documents handed out of the store can't be mutated behind its back. */
@Suppress("UNCHECKED_CAST")
fun deepCopy(v: Any?): Any? = when (v) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k.toString()] = deepCopy(x) } }
    is List<*> -> v.mapTo(mutableListOf()) { deepCopy(it) }
    else -> v
}

@Suppress("UNCHECKED_CAST")
fun Obj.copyObj(): Obj = deepCopy(this) as Obj
