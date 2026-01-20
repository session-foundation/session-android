package org.thoughtcrime.securesms.database

import android.database.Cursor
import androidx.collection.ArraySet
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.transaction
import kotlinx.serialization.json.Json
import org.session.libsession.network.model.Path
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SnodePoolStorage
import org.session.libsession.network.snode.SwarmStorage
import org.session.libsignal.utilities.ForkInfo
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.util.asSequence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SnodeDatabase @Inject constructor(
    private val helper: Provider<SupportSQLiteOpenHelper>,
    private val json: Json,
) : SwarmStorage, SnodePathStorage, SnodePoolStorage {

    private val swarmCache = ConcurrentHashMap<String, Set<Snode>>()
    private val onionPathsCache = AtomicReference<List<Path>>(null)
    private val poolCache = AtomicReference<Set<Snode>>(null)

    private val readableDatabase: SupportSQLiteDatabase get() = helper.get().readableDatabase
    private val writableDatabase: SupportSQLiteDatabase get() = helper.get().writableDatabase

    override fun getSwarm(publicKey: String): Set<Snode> {
        swarmCache.get(publicKey)?.let {
            return it
        }

        //language=roomsql
        return readableDatabase.query("""
            SELECT snodes.*
            FROM snodes
            WHERE snodes.id IN (
                SELECT snode_id
                FROM swarm_snodes
                WHERE pubkey = ?
            )
        """, arrayOf(publicKey)).use { cursor ->
            cursor.mapSnodeSequence { _, snode -> snode }
                .mapTo(ArraySet(cursor.count)) { it }
        }.also {
            swarmCache[publicKey] = it
        }
    }

    override fun setSwarm(
        publicKey: String,
        swarm: Set<Snode>
    ) {
        writableDatabase.transaction {
            // First delete existing entries for this swarm
            execSQL("DELETE FROM swarm_snodes WHERE pubkey = ?", arrayOf(publicKey))

            // Insert the swarm mapping but only for snodes that exist in the snodes table
            //language=roomsql
            compileStatement("""
                INSERT OR REPLACE INTO swarm_snodes (pubkey, snode_id)
                SELECT ?1, id
                FROM snodes
                WHERE ed25519_pub_key = ?2
            """).use { stmt ->
                swarm.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, publicKey)
                    stmt.bindString(2, snode.publicKeySet!!.ed25519Key)
                    stmt.execute()
                }
            }
        }

        swarmCache.remove(publicKey)
    }

    override fun dropSnodeFromSwarm(publicKey: String, snodeEd25519PubKey: String) {
        swarmCache.remove(publicKey)

        //language=roomsql
        writableDatabase.execSQL("""
            DELETE FROM swarm_snodes
            WHERE pubkey = ?1 AND snode_id = (
                SELECT id FROM snodes WHERE ed25519_pub_key = ?2
            )""", arrayOf(publicKey, snodeEd25519PubKey))
    }

    override fun getOnionRequestPaths(): List<Path> {
        onionPathsCache.get()?.let { return it }

        class FoldState {
            val paths = arrayListOf<MutableList<Snode>>()
            var lastPathId: Int? = null
        }

        //language=roomsql
        return readableDatabase.query("""
            SELECT onion_path_snodes.path_id, snodes.*
            FROM snodes
            INNER JOIN onion_path_snodes ON onion_path_snodes.snode_id = snodes.id
            ORDER BY path_id, onion_path_snodes.position
        """, emptyArray()).use { cursor ->
            cursor
                .mapSnodeSequence { cursor, snode ->
                    val pathId = cursor.getInt(0)
                    pathId to snode
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
            // Clear existing paths
            execSQL("DELETE FROM onion_paths WHERE 1")

            //language=roomsql
            compileStatement("""
                INSERT INTO onion_paths (id, created_at_ms)
                VALUES (?1, ?2)
            """).use { stmt ->
                val currentTime = System.currentTimeMillis()
                paths.forEachIndexed { pathIndex, _ ->
                    stmt.clearBindings()
                    stmt.bindLong(1, pathIndex.toLong())
                    stmt.bindLong(2, currentTime)
                    stmt.execute()
                }
            }

            // Insert new paths (the sql must make sure the snode exists)
            //language=roomsql
            compileStatement("""
                INSERT OR ABORT INTO onion_path_snodes (path_id, snode_id, position)
                SELECT ?1, (SELECT id FROM snodes WHERE ed25519_pub_key = ?2), ?3
            """).use { stmt ->
                paths.forEachIndexed { pathId, path ->
                    path.forEachIndexed { snodeIdx, snode ->
                        stmt.clearBindings()
                        stmt.bindLong(1, pathId.toLong())
                        stmt.bindString(2, snode.publicKeySet!!.ed25519Key)
                        stmt.bindLong(3, snodeIdx.toLong())
                        stmt.execute()
                    }
                }
            }
        }
    }

    override fun clearOnionRequestPaths() {
        onionPathsCache.set(null)
        writableDatabase.execSQL("DELETE FROM path_snodes WHERE 1")
    }

    override fun increaseOnionRequestPathStrike(
        path: Path,
        increment: Int
    ): Int? {
        //language=roomsql
        return writableDatabase.query("""
            WITH path_snode_keys AS ($PATH_KEYS_CTE_SQL)
            UPDATE onion_paths
            SET strikes = max(0, strikes + ?1)
            WHERE id = (
                SELECT path_id
                FROM path_snode_keys
                WHERE snode_keys = ?2
            )
            RETURNING strikes
        """, arrayOf<Any>(increment, path.snodeKeys())).use { cursor ->
            cursor.asSequence()
                .map { it.getInt(0) }
                .firstOrNull()
        }
    }

    override fun clearOnionRequestPathStrikes(path: Path) {
        //language=roomsql
        writableDatabase.query("""
            WITH path_snode_keys AS ($PATH_KEYS_CTE_SQL)
            UPDATE onion_paths
            SET strikes = 0
            WHERE id = (
                SELECT path_id
                FROM path_snode_keys
                WHERE snode_keys = ?1
            )
        """, arrayOf(path.snodeKeys()))
    }

    override fun getSnodePool(): Set<Snode> {
        poolCache.get()?.let { return it }

        return readableDatabase.query("SELECT * FROM snodes").use { cursor ->
            cursor.mapSnodeSequence { _, snode -> snode }.mapTo(ArraySet(cursor.count)) { it }
        }.also(poolCache::set)
    }

    override fun removeSnode(ed25519PubKey: String): Snode? {
        poolCache.set(null)

        //language=roomsql
        return writableDatabase.query("""
            DELETE FROM snodes
            WHERE ed25519_pub_key = ?1
            RETURNING *
        """, arrayOf(ed25519PubKey)).use { cursor ->
            cursor.mapSnodeSequence { _, snode -> snode }
                .firstOrNull()
        }
    }

    override fun setSnodePool(newValue: Set<Snode>) {
        poolCache.set(null)

        writableDatabase.transaction {
            // Create temp table to hold the new snode pub keys, as the amount of data may be large
            //language=roomsql
            execSQL("CREATE TEMPORARY TABLE temp_snode_keys(ed25519_pub_key TEXT PRIMARY KEY)")

            // Insert new snode pub keys into temp table
            //language=roomsql
            compileStatement("INSERT INTO temp_snode_keys(ed25519_pub_key) VALUES (?)").use { stmt ->
                newValue.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, snode.publicKeySet!!.ed25519Key)
                    stmt.execute()
                }
            }

            // Remove non-existing snodes
            //language=roomsql
            compileStatement("""
                DELETE FROM snodes
                WHERE ed25519_pub_key NOT IN (SELECT ed25519_pub_key FROM temp_snode_keys)
            """)

            // Actually inserting the new snodes, or updating the ip if they already exist
            //language=roomsql
            compileStatement("""
                INSERT INTO snodes (ed25519_pub_key, x25519_pub_key, ip, https_port)
                VALUES (?1, ?2, ?3, ?4)
                ON CONFLICT(ed25519_pub_key) DO UPDATE SET
                    ip = excluded.ip,
                    https_port = excluded.https_port,
                    strikes = 0
                WHERE snodes.ip != excluded.ip OR snodes.https_port != excluded.https_port OR snodes.strikes != 0
            """).use { stmt ->
                newValue.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, snode.publicKeySet!!.ed25519Key)
                    stmt.bindString(2, snode.publicKeySet.x25519Key)
                    stmt.bindString(3, snode.ip)
                    stmt.bindLong(4, snode.port.toLong())
                    stmt.execute()
                }
            }

            // Drop the temp table
            execSQL("DROP TABLE temp_snode_keys")
        }
    }

    override fun increaseSnodeStrike(
        snode: Snode,
        increment: Int
    ): Int? {
        //language=roomsql
        return writableDatabase.query("""
            UPDATE snodes
            SET strikes = max(0, strikes + ?1)
            WHERE ed25519_pub_key = ?2
            RETURNING strikes
        """, arrayOf<Any>(increment, snode.publicKeySet!!.ed25519Key)).use { cursor ->
            cursor.asSequence()
                .map { it.getInt(0) }
                .firstOrNull()
        }
    }

    override fun clearSnodeStrike(snode: Snode) {
        //language=roomsql
        writableDatabase.query("""
            UPDATE snodes
            SET strikes = 0
            WHERE ed25519_pub_key = ?1
        """, arrayOf<Any>(snode.publicKeySet!!.ed25519Key))
    }

    private fun Path.snodeKeys(): String {
        return joinToString(separator = ",", transform = { it.publicKeySet!!.ed25519Key })
    }

    private fun <T> Cursor.mapSnodeSequence(mapper: (Cursor, Snode) -> T): Sequence<T> {
        val ed25519Index = getColumnIndexOrThrow("ed25519_pub_key")
        val x25519Index = getColumnIndexOrThrow("x25519_pub_key")
        val ipIndex = getColumnIndexOrThrow("ip")
        val httpsPortIndex = getColumnIndexOrThrow("https_port")

        return asSequence()
            .map {
                val snode = Snode(
                    address = "https://${getString(ipIndex)}",
                    port = getInt(httpsPortIndex),
                    publicKeySet = Snode.KeySet(
                        ed25519Key = getString(ed25519Index),
                        x25519Key = getString(x25519Index)
                    ),
                    version = Snode.Version.ZERO
                )

                mapper(this, snode)
            }
    }

    companion object {

        // Common table expression for getting a deterministic representation of path in a form
        // of comma separated list of each snode's ed25519 pubkey in order of their position in the path.
        // Column: path_id, snode_keys
        //language=roomsql
        private const val PATH_KEYS_CTE_SQL = """
            SELECT ops.path_id, group_concat(snodes.ed25519_pub_key ORDER BY ops.position) AS snode_keys
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
                    db.execSQL("INSERT OR IGNORE INTO snodes (ed25519_pub_key, x25519_pub_key, ip, https_port) VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(
                            snode.publicKeySet!!.ed25519Key,
                            snode.publicKeySet.x25519Key,
                            snode.ip,
                            snode.port
                        )
                    )
                }

            // Migrate swarms
            oldSwarms.forEach { (pubkey, swarm) ->
                swarm.forEach { snode ->
                    //language=roomsql
                    db.execSQL("""
                    INSERT OR IGNORE INTO swarm_snodes (pubkey, snode_id) 
                    SELECT ?1, id FROM snodes WHERE ed25519_pub_key = ?2
                    """, arrayOf<Any>(
                        pubkey,
                        snode.publicKeySet!!.ed25519Key
                    ))
                }
            }

            // Migrate paths
            oldPaths.forEachIndexed { pathIndex, path ->
                //language=roomsql
                db.execSQL("INSERT INTO onion_paths (id, created_at_ms) VALUES (?1, ?2)",
                    arrayOf<Any>(pathIndex, System.currentTimeMillis()))

                path.forEachIndexed { snodeIndex, snode ->
                    //language=roomsql
                    db.execSQL("""
                    INSERT OR IGNORE INTO onion_path_snodes (path_id, snode_id, position) 
                    SELECT ?1, id, ?2 FROM snodes WHERE ed25519_pub_key = ?3
                    """, arrayOf<Any>(
                        pathIndex,
                        snodeIndex,
                        snode.publicKeySet!!.ed25519Key
                    ))
                }
            }

            // Removing old tables
            db.execSQL("DROP TABLE ${LokiAPIDatabase.swarmTable}")
            db.execSQL("DROP TABLE ${LokiAPIDatabase.snodePoolTable}")
            db.execSQL("DROP TABLE ${LokiAPIDatabase.onionRequestPathTable}")
        }
    }
}