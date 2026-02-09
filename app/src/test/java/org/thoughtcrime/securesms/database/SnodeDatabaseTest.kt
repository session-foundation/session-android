package org.thoughtcrime.securesms.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.session.libsignal.utilities.Snode

@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 36) // Setting min sdk 36 to use recent sqlite version as we use some modern features in the app code
class SnodeDatabaseTest {
    lateinit var db: SnodeDatabase

    @Before
    fun setUp() {
        db = createInMemorySnodeDatabase()
    }

    companion object {
        fun createInMemorySnodeDatabase(): SnodeDatabase {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        SnodeDatabase.createTableAndMigrateData(db, migrateOldData = false)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {}

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)

                        db.execSQL("PRAGMA foreign_keys=ON")
                    }
                })
                .build()

            val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)

            return SnodeDatabase(
                helper = { openHelper },
                json = Json
            )
        }
    }

    private val snodes = List(20) { idx ->
        Snode("https://1.2.3.1$idx", 5000, Snode.KeySet("edKey$idx", "xkey$idx"))
    }

    @Test
    fun `should persist snode pool`() {
        assertEquals(0, db.getSnodePool().size)
        val expected = snodes

        db.setSnodePool(expected)
        assertEquals(expected, db.getSnodePool())
    }

    @Test
    fun `should persist paths`() {
        assertEquals(0, db.getOnionRequestPaths().size)

        db.setSnodePool(snodes)

        val paths = listOf(
            listOf(snodes[0], snodes[1], snodes[2]),
            listOf(snodes[3], snodes[4])
        )

        db.setOnionRequestPaths(paths)
        assertEquals(paths, db.getOnionRequestPaths())

        // Changing the order of paths while adding a new one
        val newPaths = listOf(
            listOf(snodes[3], snodes[4]),
            listOf(snodes[5], snodes[6], snodes[7]),
        )

        db.setOnionRequestPaths(newPaths)
        assertEquals(newPaths, db.getOnionRequestPaths())
    }

    @Test
    fun `should persist path while retaining strikes`() {
        db.setSnodePool(snodes)

        val path1 = listOf(snodes[0], snodes[1], snodes[2])

        db.setOnionRequestPaths(listOf(path1))
        assertEquals(1, db.increaseOnionRequestPathStrike(path1, 1))

        val path2 = listOf(snodes[3], snodes[4])
        db.setOnionRequestPaths(listOf(path1, path2))
        assertEquals(listOf(path1, path2), db.getOnionRequestPaths())
        assertEquals(1, db.increaseOnionRequestPathStrike(path1, 0))
        assertEquals(0, db.increaseOnionRequestPathStrike(path2, 0))
    }

    @Test
    fun `should not persist paths that contain node not in snode pool`() {
        db.setSnodePool(snodes.take(2).toSet())

        val paths = listOf(
            listOf(snodes[0], snodes[1], snodes[2]),
        )

        assertThrows(RuntimeException::class.java) {
            db.setOnionRequestPaths(paths)
        }
    }

    @Test
    fun `should not persist paths that overlap`() {
        db.setSnodePool(snodes)

        val paths = listOf(
            listOf(snodes[0], snodes[1], snodes[2]),
            listOf(snodes[2], snodes[3])
        )

        assertThrows(RuntimeException::class.java) {
            db.setOnionRequestPaths(paths)
        }
    }

    @Test
    fun `should persist swarm`() {
        db.setSnodePool(snodes)

        val swarmNodes = listOf(snodes[0], snodes[1], snodes[2])

        assertEquals(0, db.getSwarm("key1").size)
        db.setSwarm("key1", swarmNodes)
        assertEquals(swarmNodes, db.getSwarm("key1"))
    }

    @Test
    fun `increase path strike works`() {
        db.setSnodePool(snodes)

        val path1 = listOf(snodes[0], snodes[1], snodes[2])
        val path2 = listOf(snodes[3], snodes[4])
        db.setOnionRequestPaths(listOf(path1, path2))

        assertEquals(1, db.increaseOnionRequestPathStrike(path1, 1))
        assertEquals(1, db.increaseOnionRequestPathStrike(path2, 1))
        assertEquals(0, db.increaseOnionRequestPathStrike(path1, -1))
    }

    @Test
    fun `increase path strikes with snode works`() {
        db.setSnodePool(snodes)

        val path1 = listOf(snodes[0], snodes[1], snodes[2])
        val path2 = listOf(snodes[3], snodes[4])
        db.setOnionRequestPaths(listOf(path1, path2))
    }

    @Test
    fun `increase snode strike works`() {
        db.setSnodePool(snodes)

        assertEquals(1, db.increaseSnodeStrike(snodes[0], 1))
        assertEquals(1, db.increaseSnodeStrike(snodes[1], 1))
    }

    @Test
    fun `drop snode works`() {
        db.setSnodePool(snodes)

        db.setSwarm("swarm1", listOf(snodes[0], snodes[1]))
        val paths = listOf(
            listOf(snodes[1], snodes[2]),
            listOf(snodes[3], snodes[4])
        )
        db.setOnionRequestPaths(paths)

        val expectingRemaining = snodes.drop(1)
        assertNotNull(db.removeSnode(snodes[0].publicKeySet!!.ed25519Key))
        assertEquals(expectingRemaining, db.getSnodePool())

        // Snode was in swarm, so it should be removed from there too
        assertEquals(listOf(snodes[1]), db.getSwarm("swarm1"))

        // Since snode is not in any path, paths should remain unchanged
        assertEquals(paths, db.getOnionRequestPaths())
    }

    @Test
    fun `drop snode with min strikes works`() {
        db.setSnodePool(snodes)

        assertEquals(4, db.increaseSnodeStrike(snodes[0], 4))
        assertEquals(3, db.increaseSnodeStrike(snodes[1], 3))

        db.removeSnodesWithStrikesGreaterThan(2)
        val expectingRemaining = snodes.drop(2)
        assertEquals(expectingRemaining, db.getSnodePool())
    }

    @Test
    fun `clear onion request paths works`() {
        db.setSnodePool(snodes)

        val paths = listOf(
            listOf(snodes[0], snodes[1]),
            listOf(snodes[2], snodes[3])
        )
        db.setOnionRequestPaths(paths)

        db.clearOnionRequestPaths()
        assertEquals(0, db.getOnionRequestPaths().size)
    }

    @Test
    fun `should be able to find random unused snodes for path`() {
        db.setSnodePool(snodes)

        val paths = listOf(
            listOf(snodes[0], snodes[1]),
            listOf(snodes[2], snodes[3])
        )
        db.setOnionRequestPaths(paths)

        val found = db.findRandomUnusedSnodesForNewPath(2)
        assertEquals(2, found.size)
        assertFalse(paths.flatMap { it }.any { it in found })
        assertTrue(found.all { it in snodes })
    }

    @Test
    fun `replace path works`() {
        db.setSnodePool(snodes)

        val oldPath = listOf(snodes[0], snodes[1])
        val newPath = listOf(snodes[2], snodes[3])

        db.setOnionRequestPaths(listOf(oldPath))
        assertEquals(2, db.increaseOnionRequestPathStrike(oldPath, 2))
        db.replaceOnionRequestPath(oldPath, newPath)

        assertEquals(2, db.increaseOnionRequestPathStrike(newPath, 0))
    }

    @Test
    fun `should not be able to remove snode used in paths`() {
        db.setSnodePool(snodes)

        val paths = listOf(
            listOf(snodes[0], snodes[1]),
            listOf(snodes[2], snodes[3])
        )
        db.setOnionRequestPaths(paths)

        assertThrows(RuntimeException::class.java) {
            db.removeSnode(snodes[0].publicKeySet!!.ed25519Key)
        }
    }

    @Test
    fun `replacing snode pool should reset their strikes`() {
        db.setSnodePool(snodes)

        assertEquals(2, db.increaseSnodeStrike(snodes[0], 2))
        assertEquals(3, db.increaseSnodeStrike(snodes[1], 3))

        db.setSnodePool(snodes)

        assertEquals(0, db.increaseSnodeStrike(snodes[0], 0))
        assertEquals(0, db.increaseSnodeStrike(snodes[1], 0))
    }

    @Test
    fun `replacing snode pool should remove path that contains non-exist snode`() {
        db.setSnodePool(snodes)

        val path1 = listOf(snodes[0], snodes[1])
        val path2 = listOf(snodes[2], snodes[3])
        db.setOnionRequestPaths(listOf(path1, path2))

        val newPool = snodes.drop(1).toSet()
        db.setSnodePool(newPool)

        assertEquals(listOf(path2), db.getOnionRequestPaths())
    }

    @Test
    fun `drop snode from swarm works`() {
        db.setSnodePool(snodes)

        db.setSwarm("swarm1", setOf(snodes[0], snodes[1], snodes[2]))

        val expectingRemaining = listOf(snodes[1], snodes[2])
        db.dropSnodeFromSwarm("swarm1", snodes[0].publicKeySet!!.ed25519Key)
        assertEquals(expectingRemaining, db.getSwarm("swarm1"))
    }
}