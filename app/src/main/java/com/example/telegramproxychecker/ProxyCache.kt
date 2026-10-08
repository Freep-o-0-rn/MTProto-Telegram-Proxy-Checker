package com.example.telegramproxychecker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * SQLite-backed runtime cache. Existing callers continue using ProxyCache.
 * Legacy SharedPreferences are imported transactionally exactly once.
 */
class ProxyCache(context: Context) {
    private val store = ProxySqliteStore.instance(context)

    fun loadProxies(): List<MtProxy> = store.loadCheckedProxies()
    fun saveProxies(proxies: List<MtProxy>) = store.saveChecked(proxies)
}

internal data class SourceInventoryCount(
    val sourceId: String,
    val count: Int,
    val fetchedAt: Long
)

internal data class SourceScanCursor(
    val lastKey: String?,
    val pass: Long
)

/** Rows deleted from persistent proxy data; source toggles and scan settings survive. */
internal data class ProxyCleanupResult(
    val checkedProxies: Int,
    val sourceEntries: Int,
    val compacted: Boolean
)

/** Shared open DB, not a new SQLite connection on every 200 ms cache update. */
internal class ProxySqliteStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "proxy_checker.sqlite", null, 1) {

    private val legacyPrefs = context.applicationContext
        .getSharedPreferences("proxy_cache", Context.MODE_PRIVATE)

    companion object {
        @Volatile private var singleton: ProxySqliteStore? = null

        fun instance(context: Context): ProxySqliteStore =
            singleton ?: synchronized(this) {
                singleton ?: ProxySqliteStore(context).also { singleton = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE checked_proxies (
                proxy_key TEXT PRIMARY KEY NOT NULL,
                protocol TEXT NOT NULL,
                source_id TEXT NOT NULL,
                original_url TEXT NOT NULL,
                server TEXT NOT NULL,
                port INTEGER NOT NULL,
                secret TEXT NOT NULL,
                username TEXT,
                password TEXT,
                tcp_ok INTEGER,
                tcp_ping INTEGER,
                telegram_ok INTEGER,
                telegram_ping INTEGER,
                telegram_error TEXT,
                checked_at INTEGER,
                favorite INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX checked_source_idx ON checked_proxies(source_id)")
        db.execSQL(
            """CREATE TABLE source_entries (
                source_id TEXT NOT NULL,
                proxy_key TEXT NOT NULL,
                protocol TEXT NOT NULL,
                server TEXT NOT NULL,
                port INTEGER NOT NULL,
                original_url TEXT NOT NULL,
                secret TEXT NOT NULL,
                username TEXT,
                password TEXT,
                PRIMARY KEY(source_id, proxy_key)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX inventory_order_idx ON source_entries(source_id, proxy_key)")
        db.execSQL(
            """CREATE TABLE source_inventory (
                source_id TEXT PRIMARY KEY NOT NULL,
                entry_count INTEGER NOT NULL,
                fetched_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE source_cursors (
                source_id TEXT PRIMARY KEY NOT NULL,
                last_key TEXT,
                pass_number INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE TABLE db_meta (meta_key TEXT PRIMARY KEY NOT NULL, meta_value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 only. Future upgrades MUST use ALTER/migration, never drop user data.
        if (oldVersion != newVersion) error("Unsupported SQLite schema migration: $oldVersion to $newVersion")
    }

    @Synchronized
    private fun importLegacyIfNeeded() {
        val db = writableDatabase
        val alreadyImported = db.rawQuery(
            "SELECT 1 FROM db_meta WHERE meta_key = ?",
            arrayOf("legacy_imported")
        ).use { it.moveToFirst() }
        if (alreadyImported) return

        val raw = legacyPrefs.getString("proxies_json", null)
        val oldEntries = if (raw == null) emptyList() else try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { index ->
                val obj = arr.getJSONObject(index)
                MtProxy(
                    originalUrl = obj.optString("originalUrl"),
                    server = obj.optString("server"),
                    port = obj.optInt("port"),
                    secret = obj.optString("secret"),
                    tcpOk = obj.boolOrNull("tcpOk"),
                    tcpPingMs = obj.longOrNull("tcpPingMs"),
                    telegramOk = obj.boolOrNull("telegramOk"),
                    telegramPingMs = obj.longOrNull("telegramPingMs"),
                    telegramError = obj.stringOrNull("telegramError"),
                    checkedAt = obj.longOrNull("checkedAt"),
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
            }
        } catch (e: Exception) {
            // Do not delete or mark a corrupted legacy backup as imported.
            // Users can still recover its contents from the old preferences.
            throw IllegalStateException("Cannot import legacy proxy cache", e)
        }

        db.beginTransaction()
        try {
            for (proxy in oldEntries) upsertChecked(db, proxy)
            val meta = ContentValues().apply {
                put("meta_key", "legacy_imported")
                put("meta_value", "1")
            }
            db.insertWithOnConflict("db_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Database commit happened before removing the legacy JSON.
        // A failed prefs commit is safe; db_meta prevents duplicate import.
        if (raw != null) legacyPrefs.edit().remove("proxies_json").commit()
    }

    /**
     * Clear every proxy-bearing SQLite table in a single transaction. Preserve
     * configuration stored in proxy_source_settings. Invalidate the legacy
     * SharedPreferences migration marker so old proxies cannot come back.
     * VACUUM outside the transaction returns unused SQLite pages to storage.
     */
    @Synchronized
    fun clearProxyData(): ProxyCleanupResult {
        val db = writableDatabase
        var checked = 0
        var sourceEntries = 0
        db.beginTransaction()
        try {
            checked = db.delete("checked_proxies", null, null)
            sourceEntries = db.delete("source_entries", null, null)
            db.delete("source_inventory", null, null)
            db.delete("source_cursors", null, null)
            // Mark the legacy cache imported without needing to parse old data.
            val meta = ContentValues().apply {
                put("meta_key", "legacy_imported")
                put("meta_value", "1")
            }
            db.insertWithOnConflict("db_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        legacyPrefs.edit().remove("proxies_json").commit()
        // Deletion is complete even if this optional disk compaction fails.
        val compacted = try {
            db.execSQL("VACUUM")
            true
        } catch (_: android.database.sqlite.SQLiteException) {
            false
        }
        return ProxyCleanupResult(checked, sourceEntries, compacted)
    }

    @Synchronized
    fun loadCheckedProxies(): List<MtProxy> {
        importLegacyIfNeeded()
        val results = ArrayList<MtProxy>()
        readableDatabase.rawQuery(
            "SELECT * FROM checked_proxies ORDER BY rowid",
            null
        ).use { cursor ->
            val cols = cursor.columnNames.withIndex().associate { it.value to it.index }
            fun str(name: String): String = cursor.getString(cols.getValue(name))
            fun strNullable(name: String): String? {
                val idx = cols.getValue(name)
                return if (cursor.isNull(idx)) null else cursor.getString(idx)
            }
            fun longNullable(name: String): Long? {
                val idx = cols.getValue(name)
                return if (cursor.isNull(idx)) null else cursor.getLong(idx)
            }
            fun boolNullable(name: String): Boolean? = longNullable(name)?.let { it != 0L }
            while (cursor.moveToNext()) {
                results += MtProxy(
                    originalUrl = str("original_url"),
                    server = str("server"),
                    port = cursor.getInt(cols.getValue("port")),
                    secret = str("secret"),
                    tcpOk = boolNullable("tcp_ok"),
                    tcpPingMs = longNullable("tcp_ping"),
                    telegramOk = boolNullable("telegram_ok"),
                    telegramPingMs = longNullable("telegram_ping"),
                    telegramError = strNullable("telegram_error"),
                    checkedAt = longNullable("checked_at"),
                    isFavorite = cursor.getInt(cols.getValue("favorite")) != 0,
                    protocol = ProxySourceProtocol.valueOf(str("protocol")),
                    username = strNullable("username"),
                    password = strNullable("password"),
                    sourceId = str("source_id")
                )
            }
        }
        return results
    }

    @Synchronized
    fun saveChecked(proxies: List<MtProxy>) {
        importLegacyIfNeeded()
        val db = writableDatabase
        db.beginTransaction()
        try {
            proxies.forEach { upsertChecked(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertChecked(db: SQLiteDatabase, p: MtProxy) {
        val values = ContentValues().apply {
            put("proxy_key", p.cacheKey)
            put("protocol", p.protocol.name)
            put("source_id", p.sourceId)
            put("original_url", p.originalUrl)
            put("server", p.server)
            put("port", p.port)
            put("secret", p.secret)
            put("username", p.username)
            put("password", p.password)
            putNullableBoolean("tcp_ok", p.tcpOk)
            put("tcp_ping", p.tcpPingMs)
            putNullableBoolean("telegram_ok", p.telegramOk)
            put("telegram_ping", p.telegramPingMs)
            put("telegram_error", p.telegramError)
            put("checked_at", p.checkedAt)
            put("favorite", if (p.isFavorite) 1 else 0)
        }
        db.insertWithOnConflict("checked_proxies", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun inventory(): Map<String, SourceInventoryCount> {
        val result = mutableMapOf<String, SourceInventoryCount>()
        readableDatabase.rawQuery(
            "SELECT source_id, entry_count, fetched_at FROM source_inventory", null
        ).use { rows ->
            while (rows.moveToNext()) {
                val info = SourceInventoryCount(rows.getString(0), rows.getInt(1), rows.getLong(2))
                result[info.sourceId] = info
            }
        }
        return result
    }

    /**
     * Replace only after download+parse succeeded. The old count and inventory
     * survive network/parsing failure or cancellation before the transaction.
     */
    @Synchronized
    fun replaceSourceInventory(sourceId: String, proxies: List<MtProxy>, fetchedAt: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("source_entries", "source_id = ?", arrayOf(sourceId))
            val insert = db.compileStatement(
                """INSERT OR REPLACE INTO source_entries
                  (source_id,proxy_key,protocol,server,port,original_url,secret,username,password)
                  VALUES (?,?,?,?,?,?,?,?,?)""".trimIndent()
            )
            try {
                for (p in proxies.distinctBy { it.cacheKey }) {
                    insert.clearBindings()
                    insert.bindString(1, sourceId)
                    insert.bindString(2, p.cacheKey)
                    insert.bindString(3, p.protocol.name)
                    insert.bindString(4, p.server)
                    insert.bindLong(5, p.port.toLong())
                    insert.bindString(6, p.originalUrl)
                    insert.bindString(7, p.secret)
                    if (p.username != null) insert.bindString(8, p.username) else insert.bindNull(8)
                    if (p.password != null) insert.bindString(9, p.password) else insert.bindNull(9)
                    insert.executeInsert()
                }
            } finally {
                insert.close()
            }
            val values = ContentValues().apply {
                put("source_id", sourceId)
                put("entry_count", proxies.distinctBy { it.cacheKey }.size)
                put("fetched_at", fetchedAt)
            }
            db.insertWithOnConflict("source_inventory", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The source_entries table keeps all feed memberships, while checked_proxies
     * stores a single scan result per protocol/server/port/secret identity.
     * Disabling one feed must not hide a proxy still present in another feed.
     */
    @Synchronized
    fun mtprotoFromSources(sourceIds: List<String>): List<MtProxy> {
        if (sourceIds.isEmpty()) return emptyList()
        val proxies = ArrayList<MtProxy>()
        for (sourceId in sourceIds.distinct()) {
            readableDatabase.rawQuery(
                """SELECT server, port, original_url, secret
                   FROM source_entries
                   WHERE source_id = ? AND protocol = ?
                   ORDER BY proxy_key""".trimIndent(),
                arrayOf(sourceId, ProxySourceProtocol.MTPROTO.name)
            ).use { rows ->
                while (rows.moveToNext()) {
                    proxies += MtProxy(
                        server = rows.getString(0),
                        port = rows.getInt(1),
                        originalUrl = rows.getString(2),
                        secret = rows.getString(3),
                        protocol = ProxySourceProtocol.MTPROTO,
                        sourceId = sourceId
                    )
                }
            }
        }
        return proxies.distinctBy { it.cacheKey }
    }

    @Synchronized
    fun uniqueInventoryCount(sourceIds: Set<String>): Int {
        if (sourceIds.isEmpty()) return 0
        val placeholders = List(sourceIds.size) { "?" }.joinToString(",")
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT proxy_key) FROM source_entries WHERE source_id IN ($placeholders)",
            sourceIds.toTypedArray()
        ).use { rows ->
            return if (rows.moveToFirst()) rows.getInt(0) else 0
        }
    }

    /**
     * A bounded, rotating selection from the current SOCKS5 feed.
     * Unchecked entries precede previously checked ones, then oldest checks first.
     * Checked timestamps survive process restarts, so the next run progresses
     * without ever loading the entire remote feed into Compose or memory.
     */
    @Synchronized
    fun nextSocksCandidates(sourceId: String, limit: Int): List<MtProxy> {
        if (limit <= 0) return emptyList()
        importLegacyIfNeeded()
        val output = ArrayList<MtProxy>(limit)
        readableDatabase.rawQuery(
            """SELECT se.server, se.port, se.original_url, se.secret, se.username, se.password
               FROM source_entries se
               LEFT JOIN checked_proxies c ON c.proxy_key = se.proxy_key
               WHERE se.source_id = ? AND se.protocol = ?
               ORDER BY CASE WHEN c.checked_at IS NULL THEN 0 ELSE 1 END ASC,
                        c.checked_at ASC, se.proxy_key ASC
               LIMIT ?""".trimIndent(),
            arrayOf(sourceId, ProxySourceProtocol.SOCKS5.name, limit.toString())
        ).use { rows ->
            while (rows.moveToNext()) {
                output += MtProxy(
                    server = rows.getString(0),
                    port = rows.getInt(1),
                    originalUrl = rows.getString(2),
                    secret = rows.getString(3),
                    username = if (rows.isNull(4)) null else rows.getString(4),
                    password = if (rows.isNull(5)) null else rows.getString(5),
                    protocol = ProxySourceProtocol.SOCKS5,
                    sourceId = sourceId
                )
            }
        }
        return output
    }

    @Synchronized
    fun cursorForSource(sourceId: String): SourceScanCursor {
        readableDatabase.rawQuery(
            "SELECT last_key, pass_number FROM source_cursors WHERE source_id = ?",
            arrayOf(sourceId)
        ).use {
            if (!it.moveToFirst()) return SourceScanCursor(null, 0L)
            return SourceScanCursor(if (it.isNull(0)) null else it.getString(0), it.getLong(1))
        }
    }

    @Synchronized
    fun saveSourceCursor(sourceId: String, cursor: SourceScanCursor) {
        val v = ContentValues().apply {
            put("source_id", sourceId)
            put("last_key", cursor.lastKey)
            put("pass_number", cursor.pass)
        }
        writableDatabase.insertWithOnConflict(
            "source_cursors", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}

private fun ContentValues.putNullableBoolean(key: String, value: Boolean?) {
    if (value == null) putNull(key) else put(key, if (value) 1 else 0)
}

private fun JSONObject.boolOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

private fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
