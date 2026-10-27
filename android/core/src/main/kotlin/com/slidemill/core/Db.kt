package com.slidemill.core

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A small JSON document store: one file per table, kept in memory, written atomically after each
 * transaction that changed it. The phone's data is a few thousand rows at most, so this beats
 * shipping a database (and a backup is just a zip of the folder).
 *
 * Rows use the same field names as the Python server's tables. Timestamps are epoch millis.
 */
class Db(val dir: File) {
    val tables = listOf(
        "products", "collections", "images", "automations", "slideshows", "posts", "settings",
        "library_profiles", "library_posts", "jobs", "state",
        "my_posts", "post_snapshots", "account_snapshots",
    )
    private val data = HashMap<String, LinkedHashMap<String, Obj>>()
    private val written = HashMap<String, String>()

    init {
        dir.mkdirs()
        for (t in tables) {
            val f = File(dir, "$t.json")
            val rows = LinkedHashMap<String, Obj>()
            if (f.isFile) {
                val text = f.readText()
                @Suppress("UNCHECKED_CAST")
                (Json.parse(text) as? List<Any?>)?.forEach { r -> (r as? Obj)?.let { rows[it.str("id")] = it } }
                written[t] = text
            }
            data[t] = rows
        }
    }

    /** Run `block` with exclusive access; tables it touched are saved afterwards if they changed. */
    fun <T> tx(block: Tx.() -> T): T = synchronized(this) {
        val tx = Tx()
        try {
            tx.block()
        } finally {
            for (t in tx.touched) save(t)
        }
    }

    private fun save(table: String) {
        val text = Json.write(data.getValue(table).values)
        if (written[table] == text) return
        val target = File(dir, "$table.json")
        val tmp = File(dir, "$table.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        written[table] = text
    }

    /** Re-read everything from disk (after a backup restore). */
    fun reload() = synchronized(this) {
        written.clear()
        for (t in tables) {
            val rows = LinkedHashMap<String, Obj>()
            val f = File(dir, "$t.json")
            if (f.isFile) {
                val text = f.readText()
                @Suppress("UNCHECKED_CAST")
                (Json.parse(text) as? List<Any?>)?.forEach { r -> (r as? Obj)?.let { rows[it.str("id")] = it } }
                written[t] = text
            }
            data[t] = rows
        }
    }

    inner class Tx {
        val touched = HashSet<String>()

        private fun table(name: String): LinkedHashMap<String, Obj> {
            touched += name
            return data[name] ?: error("unknown table $name")
        }

        /** The live row: changes made inside the transaction are saved. */
        fun get(table: String, id: String?): Obj? = if (id.isNullOrEmpty()) null else table(table)[id]

        /** Every row, oldest first (insertion order). */
        fun all(table: String): List<Obj> = table(table).values.toList()

        fun where(table: String, pred: (Obj) -> Boolean): List<Obj> = table(table).values.filter(pred)

        fun insert(table: String, row: Obj): Obj {
            if (row.str("id").isEmpty()) row["id"] = newId()
            if (row["created_at"] == null) row["created_at"] = nowMs()
            table(table)[row.str("id")] = row
            return row
        }

        fun delete(table: String, id: String): Obj? = table(table).remove(id)

        fun deleteWhere(table: String, pred: (Obj) -> Boolean): Int {
            val t = table(table)
            val ids = t.values.filter(pred).map { it.str("id") }
            ids.forEach { t.remove(it) }
            return ids.size
        }
    }

    companion object {
        /** Overridable clock for tests. */
        @Volatile var clock: () -> Instant = { Instant.now() }

        fun nowMs(): Long = clock().toEpochMilli()

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")

        /** Python-style isoformat ("2026-10-24T12:00:00.000000+00:00"). */
        fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).format(ISO) }

        fun iso(instant: Instant): String = instant.atOffset(ZoneOffset.UTC).format(ISO)

        /** Epoch seconds of an [iso] string (or any ISO 8601 time with an offset); null if it isn't one. */
        fun epochS(iso: String): Long? = runCatching { java.time.OffsetDateTime.parse(iso).toEpochSecond() }.getOrNull()
    }
}
