package org.thoughtcrime.securesms.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.transaction
import kotlinx.serialization.json.Json
import org.session.libsession.network.model.Path
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SnodePoolStorage
import org.session.libsession.network.snode.SwarmStorage
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.asSequence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.min

/**
 * A database interface for storing snode related data: the snode pool, swarms and onion request
 * paths. It consists of 3 tables:
 * - snodes: stores all known snodes, including those in the pool, swarms and paths.
 *      Each snode has a strike count, which can be used to track misbehaving snodes and remove
 *      them from the pool if necessary.
 *
 * - swarm_snodes: a mapping table between swarms (identified by pubkey) and snodes (identified by id).
 *      This table references snodes with a foreign key, so if a snode is removed from the snodes
 *      table, it will be automatically removed from all swarms as well.
 *
 * - onion_paths: stores onion request paths. Each path has a strike count as well, which can be used
 *     to track bad paths and remove them if necessary.
 *
 * - onion_path_snodes: a mapping table between onion paths (identified by path_id) and snodes (identified by id).
 *    This table references snodes with a foreign key, but with RESTRICT delete behavior, meaning
 *    that a snode that is part of an onion path cannot be deleted from the snodes table until
 *    it is removed from the path.
 *    This is to prevent accidentally deleting snodes that are still in use in paths, as that could
 *    lead to loss of all paths that contain that snode.
 *
 */
@Singleton
class SnodeDatabase @Inject constructor(
    private val helper: Provider<SupportSQLiteOpenHelper>,
    private val json: Json,
) : SwarmStorage, SnodePathStorage, SnodePoolStorage {

    private val swarmCache = ConcurrentHashMap<String, List<Snode>>()
    private val onionPathsCache = AtomicReference<List<Path>>(null)
    private val poolCache = AtomicReference<List<Snode>>(null)

    private val readableDatabase: SupportSQLiteDatabase get() = helper.get().readableDatabase
    private val writableDatabase: SupportSQLiteDatabase get() = helper.get().writableDatabase

    override fun getSwarm(publicKey: String): List<Snode> {
        swarmCache.get(publicKey)?.let {
            return it
        }

        //language=roomsql
        return readableDatabase.query(
            """
            SELECT snodes.*
            FROM snodes
            WHERE snodes.id IN (
                SELECT snode_id
                FROM swarm_snodes
                WHERE pubkey = ?
            )
        """, arrayOf(publicKey)
        ).use { cursor ->
            val indices = SnodeColumnIndices(cursor)

            cursor.asSequence()
                .mapTo(arrayListOf()) { it.toSnode(indices) }
        }.also {
            swarmCache[publicKey] = it
        }
    }

    override fun setSwarm(
        publicKey: String,
        swarm: Collection<Snode>
    ) {
        writableDatabase.transaction {
            // First delete existing entries for this swarm
            execSQL("DELETE FROM swarm_snodes WHERE pubkey = ?", arrayOf(publicKey))

            // Insert the swarm mapping but only for snodes that exist in the snodes table
            //language=roomsql
            compileStatement(
                """
                INSERT OR REPLACE INTO swarm_snodes (pubkey, snode_id)
                SELECT ?1, id
                FROM snodes
                WHERE ed25519_pub_key = ?2
            """
            ).use { stmt ->
                swarm.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, publicKey)
                    stmt.bindString(2, snode.ed25519Key)
                    stmt.execute()
                }
            }
        }

        swarmCache.remove(publicKey)
    }

    override fun dropSnodeFromSwarm(publicKey: String, snodeEd25519PubKey: String) {
        swarmCache.remove(publicKey)

        //language=roomsql
        writableDatabase.execSQL(
            """
            DELETE FROM swarm_snodes
            WHERE pubkey = ?1 AND snode_id = (
                SELECT id FROM snodes WHERE ed25519_pub_key = ?2
            )""", arrayOf(publicKey, snodeEd25519PubKey)
        )
    }

    override fun getOnionRequestPaths(): List<Path> {
        onionPathsCache.get()?.let { return it }

        class FoldState {
            val paths = arrayListOf<MutableList<Snode>>()
            var lastPathId: Int? = null
        }

        //language=roomsql
        return readableDatabase.query(
            """
            SELECT onion_path_snodes.path_id, snodes.*
            FROM snodes
            INNER JOIN onion_path_snodes ON onion_path_snodes.snode_id = snodes.id
            ORDER BY path_id, onion_path_snodes.position
        """, emptyArray()
        ).use { cursor ->
            val indices = SnodeColumnIndices(cursor)
            cursor
                .asSequence()
                .map {
                    val pathId = cursor.getInt(0)
                    pathId to cursor.toSnode(indices)
                }
                .fold(FoldState()) { state, (pathId, snode) ->
                    if (state.lastPathId == pathId) {
                        state.paths.last() += snode
                    } else {
                        state.paths += mutableListOf(snode)
                    }

                    state.lastPathId = pathId
                    state
                }
                .paths
        }.also(onionPathsCache::set)
    }

    override fun setOnionRequestPaths(paths: List<Path>) {
        onionPathsCache.set(null)
        writableDatabase.transaction {
            class PathInfo(
                val strike: Int,
                val createdAtMs: Long,
            )

            //language=roomsql
            val existingPathInfoByPathKey = query("""
                WITH path_keys AS ($PATH_KEYS_CTE_SQL)
                SELECT path_key, strikes, created_at_ms FROM onion_paths
                INNER JOIN path_keys ON onion_paths.id = path_keys.path_id
            """).use { cursor ->
                cursor.asSequence()
                    .associate {
                        val pathKey = cursor.getString(0)
                        pathKey to PathInfo(
                            strike = cursor.getInt(1),
                            createdAtMs = cursor.getLong(2),
                        )
                    }
            }

            // Clear all paths
            //language=roomsql
            execSQL("DELETE FROM onion_paths WHERE 1")

            // Insert all paths, retaining path info where applicable
            //language=roomsql
            compileStatement("INSERT INTO onion_paths (id, created_at_ms, strikes) VALUES (?1, ?2, ?3)")
                .use { pathInsertStmt ->
                    compileStatement(
                        """
                            INSERT OR ABORT INTO onion_path_snodes (path_id, snode_id, position)
                            SELECT ?1, (SELECT id FROM snodes WHERE ed25519_pub_key = ?2), ?3
                        """
                    ).use { pathSnodeInsertStmt ->
                        paths.forEachIndexed { pathIdx, path ->
                            val pathKey = path.pathKey()
                            val existingInfo = existingPathInfoByPathKey[pathKey]

                            pathInsertStmt.clearBindings()
                            pathInsertStmt.bindLong(1, pathIdx.toLong())
                            pathInsertStmt.bindLong(2, existingInfo?.createdAtMs ?: System.currentTimeMillis())
                            pathInsertStmt.bindLong(3, existingInfo?.strike?.toLong() ?: 0L)
                            pathInsertStmt.execute()

                            path.forEachIndexed { snodeIdx, snode ->
                                pathSnodeInsertStmt.clearBindings()
                                pathSnodeInsertStmt.bindLong(1, pathIdx.toLong())
                                pathSnodeInsertStmt.bindString(2, snode.ed25519Key)
                                pathSnodeInsertStmt.bindLong(3, snodeIdx.toLong())
                                pathSnodeInsertStmt.execute()
                            }
                        }
                    }
            }
        }
    }

    override fun replaceOnionRequestPath(
        oldPath: Path,
        newPath: Path
    ) {
        if (oldPath == newPath) {
            return
        }

        onionPathsCache.set(null)
        writableDatabase.transaction {
            //language=roomsql
            val pathId = query(
                """
                WITH path_keys AS ($PATH_KEYS_CTE_SQL) 
                SELECT path_id FROM path_keys WHERE path_key = ?1
                """, arrayOf(oldPath.pathKey())
            ).use { cursor ->
                check(cursor.moveToNext()) { "Old path does not exist" }

                cursor.getInt(0)
            }

            // Delete all existing snodes for the path
            //language=roomsql
            execSQL(
                "DELETE FROM onion_path_snodes WHERE path_id = ?1",
                arrayOf(pathId)
            )

            // Insert new snodes for the path
            //language=roomsql
            compileStatement("""
                INSERT OR ABORT INTO onion_path_snodes (path_id, snode_id, position)
                SELECT ?1, (SELECT id FROM snodes WHERE ed25519_pub_key = ?2), ?3
            """).use { stmt ->
                newPath.forEachIndexed { snodeIdx, snode ->
                    stmt.clearBindings()
                    stmt.bindLong(1, pathId.toLong())
                    stmt.bindString(2, snode.ed25519Key)
                    stmt.bindLong(3, snodeIdx.toLong())
                    stmt.execute()
                }
            }
        }
    }

    override fun removePath(path: Path) {
        onionPathsCache.set(null)

        //language=roomsql
        writableDatabase.execSQL(
            """
            WITH path_keys AS ($PATH_KEYS_CTE_SQL)
            DELETE FROM onion_paths
            WHERE id = (
                SELECT path_id
                FROM path_keys
                WHERE path_key = ?1
            )
        """, arrayOf(path.pathKey())
        )
    }

    override fun clearOnionRequestPaths() {
        onionPathsCache.set(null)
        //language=roomsql
        writableDatabase.execSQL("DELETE FROM onion_path_snodes WHERE 1")
    }

    override fun increaseOnionRequestPathStrike(
        path: Path,
        increment: Int
    ): Int? {
        //language=roomsql
        return writableDatabase.query(
            """
            WITH path_keys AS ($PATH_KEYS_CTE_SQL)
            UPDATE onion_paths
            SET strikes = max(0, strikes + ?1)
            WHERE id = (
                SELECT path_id
                FROM path_keys
                WHERE path_key = ?2
            )
            RETURNING strikes
        """, arrayOf<Any>(increment, path.pathKey())
        ).use { cursor ->
            cursor.asSequence()
                .map { it.getInt(0) }
                .firstOrNull()
        }
    }

    override fun increaseOnionRequestPathStrikeContainingSnode(
        snodeEd25519PubKey: String,
        increment: Int
    ): Pair<Path, Int>? {
        return writableDatabase.transaction {
            //language=roomsql
            val (pathId, newStrikes) = query(
                """
                UPDATE onion_paths
                SET strikes = max(0, strikes + ?1)
                WHERE id IN (
                    SELECT ops.path_id
                    FROM onion_path_snodes AS ops
                    INNER JOIN snodes ON ops.snode_id = snodes.id
                    WHERE snodes.ed25519_pub_key = ?2
                )
                RETURNING id, strikes
            """, arrayOf<Any>(increment, snodeEd25519PubKey)
            ).use { cursor ->
                if (!cursor.moveToNext()) return null

                cursor.getInt(0) to cursor.getInt(1)
            }

            //language=roomsql
            query(
                """
                SELECT snodes.*
                FROM snodes
                INNER JOIN onion_path_snodes ON onion_path_snodes.snode_id = snodes.id
                WHERE onion_path_snodes.path_id = ?1
                ORDER BY onion_path_snodes.position
            """, arrayOf(pathId)
            ).use { cursor ->
                cursor.toSnodeList() to newStrikes
            }
        }
    }

    override fun findRandomUnusedSnodesForNewPath(n: Int): List<Snode> {
        require(n > 0) {
            "n(number of snodes) must be positive"
        }

        //language=roomsql
        return readableDatabase.query(
            """
            SELECT * FROM snodes
            WHERE id NOT IN (SELECT snode_id FROM onion_path_snodes)
        """
        ).use { cursor ->
            val totalAvailable = cursor.count
            generateSequence { (0 until totalAvailable).random() }
                .distinct()
                .take(
                    min(
                        totalAvailable,
                        n
                    )
                ) // In case there are less available snodes than requested
                .mapTo(ArrayList(2)) { randomIndex ->
                    cursor.moveToPosition(randomIndex)
                    cursor.toSnode()
                }
        }
    }

    override fun getSnodePool(): List<Snode> {
        poolCache.get()?.let { return it }

        return readableDatabase.query("SELECT * FROM snodes").use { cursor ->
            cursor.toSnodeList()
        }.also(poolCache::set)
    }

    override fun removeSnode(ed25519PubKey: String): Snode? {
        poolCache.set(null)
        swarmCache.clear() // Removing a snode may affect multiple swarms

        //language=roomsql
        return writableDatabase.query(
            """
            DELETE FROM snodes
            WHERE ed25519_pub_key = ?1
            RETURNING *
        """, arrayOf(ed25519PubKey)
        ).use { cursor ->
            cursor
                .asSequence()
                .map { it.toSnode() }
                .firstOrNull()
        }
    }

    override fun setSnodePool(newValue: Collection<Snode>) {
        poolCache.set(null)
        onionPathsCache.set(null)
        swarmCache.clear()

        writableDatabase.transaction {
            // Create temp table to hold the new snode pub keys, as the amount of data may be large
            //language=roomsql
            execSQL("CREATE TEMPORARY TABLE temp_snode_keys(ed25519_pub_key TEXT PRIMARY KEY)")

            // Insert new snode pub keys into temp table
            //language=roomsql
            compileStatement("INSERT INTO temp_snode_keys(ed25519_pub_key) VALUES (?)").use { stmt ->
                newValue.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, snode.ed25519Key)
                    stmt.execute()
                }
            }

            // Delete paths that reference snodes not in the new pool
            //language=roomsql
            execSQL("""
               DELETE FROM onion_paths 
               WHERE id IN (
                   SELECT ops.path_id
                   FROM onion_path_snodes AS ops
                   INNER JOIN snodes ON ops.snode_id = snodes.id
                   WHERE snodes.ed25519_pub_key NOT IN (SELECT ed25519_pub_key FROM temp_snode_keys)
               )
            """)

            // Remove non-existing snodes
            //language=roomsql
            compileStatement(
                """
                DELETE FROM snodes
                WHERE ed25519_pub_key NOT IN (SELECT ed25519_pub_key FROM temp_snode_keys)
            """
            )

            // Actually inserting the new snodes, or updating the ip if they already exist
            //language=roomsql
            compileStatement(
                """
                INSERT INTO snodes (ed25519_pub_key, x25519_pub_key, ip, https_port)
                VALUES (?1, ?2, ?3, ?4)
                ON CONFLICT(ed25519_pub_key) DO UPDATE SET
                    ip = excluded.ip,
                    https_port = excluded.https_port,
                    strikes = 0
                WHERE snodes.ip != excluded.ip OR snodes.https_port != excluded.https_port OR snodes.strikes != 0
            """
            ).use { stmt ->
                newValue.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, snode.ed25519Key)
                    stmt.bindString(2, snode.x25519Key)
                    stmt.bindString(3, snode.ip)
                    stmt.bindLong(4, snode.port.toLong())
                    stmt.execute()
                }
            }

            // Drop the temp table
            execSQL("DROP TABLE temp_snode_keys")
        }
    }

    override fun removeSnodesWithStrikesGreaterThan(n: Int): Int {
        return writableDatabase.transaction {
            //language=roomsql
            val numDeleted = compileStatement("DELETE FROM snodes WHERE strikes > ?1").use { stmt ->
                stmt.bindLong(1, n.toLong())
                stmt.executeUpdateDelete()
            }

            if (numDeleted > 0) {
                poolCache.set(null)
                swarmCache.clear() // Removing snodes may affect multiple swarms
            }

            numDeleted
        }
    }

    override fun increaseSnodeStrike(
        snode: Snode,
        increment: Int
    ): Int? {
        //language=roomsql
        return writableDatabase.query(
            """
            UPDATE snodes
            SET strikes = max(0, strikes + ?1)
            WHERE ed25519_pub_key = ?2
            RETURNING strikes
        """, arrayOf<Any>(increment, snode.ed25519Key)
        ).use { cursor ->
            cursor.asSequence()
                .map { it.getInt(0) }
                .firstOrNull()
        }
    }

    private fun Path.pathKey(): String {
        return joinToString(separator = ",", transform = { it.ed25519Key })
    }

    private class SnodeColumnIndices(
        val ed25519Index: Int,
        val x25519Index: Int,
        val ipIndex: Int,
        val httpsPortIndex: Int
    ) {
        constructor(cursor: Cursor) :
                this(
                    ed25519Index = cursor.getColumnIndexOrThrow("ed25519_pub_key"),
                    x25519Index = cursor.getColumnIndexOrThrow("x25519_pub_key"),
                    ipIndex = cursor.getColumnIndexOrThrow("ip"),
                    httpsPortIndex = cursor.getColumnIndexOrThrow("https_port")
                )
    }

    private fun Cursor.toSnode(indices: SnodeColumnIndices = SnodeDatabase.SnodeColumnIndices(this)): Snode {
        return Snode(
            address = "https://${getString(indices.ipIndex)}",
            port = getInt(indices.httpsPortIndex),
            publicKeySet = Snode.KeySet(
                ed25519Key = getString(indices.ed25519Index),
                x25519Key = getString(indices.x25519Index)
            ),
        )
    }

    private fun Cursor.toSnodeList(): List<Snode> {
        val indices = SnodeColumnIndices(this)
        return asSequence()
            .mapTo(ArrayList(count)) { toSnode(indices) }
    }


    companion object {

        // Common table expression for getting a deterministic representation of path in a form
        // of comma separated list of each snode's ed25519 pubkey in order of their position in the path.
        // Column: path_id, path_key
        //language=roomsql
        private const val PATH_KEYS_CTE_SQL = """
            SELECT ops.path_id, group_concat(snodes.ed25519_pub_key ORDER BY ops.position) AS path_key
            FROM onion_path_snodes AS ops
            INNER JOIN snodes ON ops.snode_id = snodes.id
            GROUP BY ops.path_id
        """

        @Suppress("DEPRECATION")
        fun createTableAndMigrateData(
            db: SupportSQLiteDatabase,
            migrateOldData: Boolean = true, // Only set to false for tests
        ) {
            //language=roomsql
            arrayOf(
                """
                    CREATE TABLE snodes(
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        ed25519_pub_key TEXT NOT NULL,
                        x25519_pub_key TEXT NOT NULL,
                        ip TEXT NOT NULL,
                        https_port INTEGER NOT NULL,
                        strikes INTEGER NOT NULL DEFAULT 0
                    ) 
                """,
                "CREATE UNIQUE INDEX index_snodes_on_ed25519_pub_key ON snodes(ed25519_pub_key)",
                "CREATE UNIQUE INDEX index_snodes_on_x25519_pub_key ON snodes(x25519_pub_key)",

                """
                    CREATE TABLE swarm_snodes(
                        pubkey TEXT NOT NULL,
                        snode_id INTEGER NOT NULL REFERENCES snodes(id) ON DELETE CASCADE,
                        PRIMARY KEY(pubkey, snode_id)
                    )
                """,

                """
                    CREATE TABLE onion_paths(
                        id INTEGER NOT NULL PRIMARY KEY,
                        created_at_ms INTEGER NOT NULL,
                        strikes INTEGER NOT NULL DEFAULT 0
                    )
                """,

                """
                    CREATE TABLE onion_path_snodes(
                        path_id INTEGER NOT NULL REFERENCES onion_paths(id) ON DELETE CASCADE,
                        snode_id INTEGER NOT NULL REFERENCES snodes(id) ON DELETE RESTRICT,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(path_id, snode_id, position)
                    )
                """,

                "CREATE INDEX path_snodes_idx_path_id ON onion_path_snodes(path_id)",
                "CREATE UNIQUE INDEX path_snodes_idx_unique_snode ON onion_path_snodes(path_id, snode_id)",
                "CREATE UNIQUE INDEX path_snodes_idx_disjoint ON onion_path_snodes(snode_id)"
            ).forEach { sql ->
                db.execSQL(sql)
            }

            if (!migrateOldData) {
                return
            }

            // Migrate existing data:
            // Note that the new db structure implies that snode pool contains ALL snodes used in
            // swarms and paths. No such guarantee existed before, so we will merge the data from
            // swarms and paths into the snode pool here, to avoid losing any snodes.
            val oldSnodePool = LokiAPIDatabase.getSnodePool(db)
            val oldSwarms = LokiAPIDatabase.getSwarms(db)
            val oldPaths = LokiAPIDatabase.getOnionRequestPaths(db)

            oldSnodePool.asSequence()
                .plus(oldSwarms.asSequence().flatMap { it.value.asSequence() })
                .plus(oldPaths.asSequence().flatMap { it.asSequence() })
                .forEach { snode ->
                    //language=roomsql
                    db.execSQL(
                        "INSERT OR IGNORE INTO snodes (ed25519_pub_key, x25519_pub_key, ip, https_port) VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(snode.ed25519Key, snode.x25519Key, snode.ip, snode.port)
                    )
                }

            // Migrate swarms
            oldSwarms.forEach { (pubkey, swarm) ->
                swarm.forEach { snode ->
                    //language=roomsql
                    db.execSQL(
                        """
                    INSERT OR IGNORE INTO swarm_snodes (pubkey, snode_id) 
                    SELECT ?1, id FROM snodes WHERE ed25519_pub_key = ?2
                    """, arrayOf<Any>(
                            pubkey,
                            snode.ed25519Key
                        )
                    )
                }
            }

            // Migrate paths
            oldPaths.forEachIndexed { pathIndex, path ->
                //language=roomsql
                db.execSQL(
                    "INSERT INTO onion_paths (id, created_at_ms) VALUES (?1, ?2)",
                    arrayOf<Any>(pathIndex, System.currentTimeMillis())
                )

                path.forEachIndexed { snodeIndex, snode ->
                    //language=roomsql
                    db.execSQL(
                        """
                    INSERT OR IGNORE INTO onion_path_snodes (path_id, snode_id, position) 
                    SELECT ?1, id, ?2 FROM snodes WHERE ed25519_pub_key = ?3
                    """, arrayOf<Any>(
                            pathIndex,
                            snodeIndex,
                            snode.ed25519Key
                        )
                    )
                }
            }

            // Removing old tables
            db.execSQL("DROP TABLE ${LokiAPIDatabase.swarmTable}")
            db.execSQL("DROP TABLE ${LokiAPIDatabase.snodePoolTable}")
            db.execSQL("DROP TABLE ${LokiAPIDatabase.onionRequestPathTable}")
        }
    }
}