package org.thoughtcrime.securesms.database

import android.app.Application
import android.database.Cursor
import androidx.collection.ArraySet
import androidx.collection.arraySetOf
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.transaction
import kotlinx.serialization.json.Json
import org.session.libsession.network.model.Path
import org.session.libsession.network.snode.SnodePathStorage
import org.session.libsession.network.snode.SnodePoolStorage
import org.session.libsession.network.snode.SwarmStorage
import org.session.libsignal.utilities.ForkInfo
import org.session.libsignal.utilities.Snode
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.util.asSequence
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.collections.arrayListOf

@Singleton
class SnodeDatabase @Inject constructor(
    application: Application,
    helper: Provider<SQLCipherOpenHelper>,
    private val json: Json,
) : Database(application, helper), SwarmStorage, SnodePathStorage, SnodePoolStorage {

    override fun getSwarm(publicKey: String): Set<Snode> {
        //language=roomsql
        return readableDatabase.rawQuery("""
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
            execSQL("""
                INSERT OR REPLACE INTO swarm_snodes (pubkey, snode_id)
                SELECT ?1, id
                FROM snodes
                WHERE ed25519_pub_key IN (SELECT value FROM json_each(?2))
            """, arrayOf(
                publicKey,
                json.encodeToString(swarm.mapNotNull { it.publicKeySet?.ed25519Key })
            ))
        }
    }

    override fun getOnionRequestPaths(): List<Path> {
        class FoldState {
            val paths = arrayListOf<MutableList<Snode>>()
            var lastPathId: Int? = null
        }

        //language=roomsql
        return readableDatabase.rawQuery("""
            SELECT path_snodes.path_id, snodes.*
            FROM snodes
            INNER JOIN path_snodes ON snodes.id = path_snodes.snode_id
            ORDER BY path_id, path_snodes.position
        """, emptyArray()).use { cursor ->
            cursor
                .mapSnodeSequence { cursor, snode ->
                    val pathId = cursor.getInt(0)
                    pathId to snode
                }
                .fold(FoldState()) { state, (pathId, snode) ->
                    if (state?.lastPathId == pathId) {
                        state.paths.last() += snode
                    } else {
                        state.paths += mutableListOf(snode)
                    }

                    state.lastPathId = pathId
                    state
                }
                .paths
        }
    }

    override fun setOnionRequestPaths(paths: List<Path>) {
        writableDatabase.transaction {
            // Clear existing paths
            execSQL("DELETE FROM path_snodes WHERE 1")

            // Insert new paths (the sql must make sure the snode exists)
            //language=roomsql
            compileStatement("""
                INSERT OR ABORT INTO path_snodes (path_id, snode_id, position)
                SELECT ?1, (SELECT id FROM snodes WHERE ed25519_pub_key = ?2), ?3
            """).use { stmt ->
                paths.forEachIndexed { pathIndex, path ->
                    path.forEachIndexed { snodeIndex, snode ->
                        stmt.clearBindings()
                        stmt.bindLong(1, pathIndex.toLong())
                        stmt.bindString(2, snode.publicKeySet!!.ed25519Key)
                        stmt.bindLong(3, snodeIndex.toLong())
                        stmt.execute()
                    }
                }
            }
        }
    }

    override fun clearOnionRequestPaths() {
        writableDatabase.execSQL("DELETE FROM path_snodes WHERE 1")
    }

    override fun getSnodePool(): Set<Snode> {
        return readableDatabase.rawQuery("SELECT * FROM snodes").use { cursor ->
            cursor.mapSnodeSequence { _, snode -> snode }.mapTo(ArraySet(cursor.count)) { it }
        }
    }

    override fun setSnodePool(newValue: Set<Snode>) {
        writableDatabase.transaction {
            // Create temp table to hold the new snode pub keys, as the amount of data may be large
            execSQL("CREATE TEMPORARY TABLE temp_snode_keys(ed25519_pub_key TEXT PRIMARY KEY)")

            // Insert new snode pub keys into temp table
            compileStatement("INSERT INTO temp_snode_keys(ed25519_pub_key) VALUES (?)").use { stmt ->
                newValue.forEach { snode ->
                    stmt.clearBindings()
                    stmt.bindString(1, snode.publicKeySet!!.ed25519Key)
                    stmt.execute()
                }
            }

            // Remove non-existing snodes
            compileStatement("""
                DELETE FROM snodes
                WHERE ed25519_pub_key NOT IN (SELECT ed25519_pub_key FROM temp_snode_keys)
            """)

            // Actually inserting the new snodes, or updating the ip if they already exist
            //language=roomsql
            compileStatement("""
                INSERT INTO snodes (ed25519_pub_key, x25519_pub_key, ip, https_port)
                VALUES (?1, ?2, ?3, ?4)
                ON CONFLICT DO UPDATE SET
                    ip = excluded.ip,
                    https_port = excluded.https_port
                WHERE snodes.ip != excluded.ip OR snodes.https_port != excluded.https_port
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

    override fun getForkInfo(): ForkInfo {
        TODO("Not yet implemented")
    }

    override fun setForkInfo(forkInfo: ForkInfo) {
        TODO("Not yet implemented")
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

        @Suppress("DEPRECATION")
        fun createTableAndMigrateData(db: SupportSQLiteDatabase) {
            //language=roomsql
            arrayOf(
                """
                    CREATE TABLE snodes(
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        ed25519_pub_key TEXT NOT NULL,
                        x25519_pub_key TEXT NOT NULL,
                        ip TEXT NOT NULL,
                        https_port INTEGER NOT NULL
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
                    CREATE TABLE path_snodes(
                        path_id INTEGER NOT NULL,
                        snode_id INTEGER NOT NULL REFERENCES snodes(id) ON DELETE RESTRICT,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(path_id, snode_id, position)
                    )
                """,

                "CREATE INDEX path_snodes_idx_on_snode_id ON path_snodes(snode_id)",
                "CREATE UNIQUE INDEX path_snodes_idx_unique_snode ON path_snodes(path_id, snode_id)",
                "CREATE UNIQUE INDEX path_snodes_idx_disjoint ON path_snodes(snode_id)"
            ).forEach { sql ->
                db.execSQL(sql)
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
                path.forEachIndexed { snodeIndex, snode ->
                    //language=roomsql
                    db.execSQL("""
                    INSERT OR IGNORE INTO path_snodes (path_id, snode_id, position) 
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