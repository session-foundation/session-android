package org.thoughtcrime.securesms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The filename `ScreenLockActionBarActivity` caches a shared file under is the sending app's
 * `OpenableColumns.DISPLAY_NAME`, verbatim and unvalidated.
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntentCacheFilenameTest {

    private val cacheDir = ApplicationProvider.getApplicationContext<Context>().cacheDir

    private fun cachedFileFor(displayName: String): File? =
        cacheFilenameFrom(displayName)?.let { File(cacheDir, it) }

    @Test
    fun `a traversing display name lands in the cache directory`() {
        val cached = cachedFileFor("../../outside.xml")

        assertThat(cached).isNotNull()
        assertThat(cached!!.name).isEqualTo("outside.xml")
        assertThat(cached.canonicalFile.parentFile).isEqualTo(cacheDir.canonicalFile)
    }

    @Test
    fun `no display name can land outside the cache directory`() {
        val displayNames = listOf(
            "../../outside.xml",
            "../outside.xml",
            "a/b/../../../../outside.xml",
            "/an/absolute/path/outside.xml",
            "subdir/outside.xml",
            "foo/",
            "..",
            ".",
            "",
            "./../outside.xml",
        )

        displayNames.forEach { displayName ->
            val cached = cachedFileFor(displayName)

            if (cached != null) {
                assertThat(cached.canonicalFile.parentFile).isEqualTo(cacheDir.canonicalFile)
            }
        }
    }

    @Test
    fun `an ordinary display name is kept as it is`() {
        assertThat(cacheFilenameFrom("cat.jpeg")).isEqualTo("cat.jpeg")
        assertThat(cacheFilenameFrom("holiday photo (1).png")).isEqualTo("holiday photo (1).png")
        assertThat(cacheFilenameFrom("..leading-dots.pdf")).isEqualTo("..leading-dots.pdf")
        assertThat(cacheFilenameFrom("report.pdf/")).isEqualTo("report.pdf")
    }

    @Test
    fun `a display name that reduces to no filename at all is refused`() {
        assertThat(cacheFilenameFrom("")).isNull()
        assertThat(cacheFilenameFrom(".")).isNull()
        assertThat(cacheFilenameFrom("..")).isNull()
        assertThat(cacheFilenameFrom("../..")).isNull()
        assertThat(cacheFilenameFrom("/")).isNull()
    }
}
