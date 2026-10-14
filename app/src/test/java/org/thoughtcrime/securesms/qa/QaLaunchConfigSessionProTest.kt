package org.thoughtcrime.securesms.qa

import android.content.Intent
import io.mockk.mockk
import io.mockk.verify
import network.loki.messenger.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.session.libsession.network.snode.SnodeDirectory
import org.session.libsession.utilities.TextSecurePreferences

/**
 * Covers the `sessionPro` launch extra, the only way a harness can turn the Pro gate on now that it is off by
 * default in every build.
 */
@RunWith(RobolectricTestRunner::class)
class QaLaunchConfigSessionProTest {

    private lateinit var prefs: TextSecurePreferences
    private lateinit var snodeDirectory: SnodeDirectory

    @Before
    fun setUp() {
        // The whole class is compiled out when this is false, so every assertion below would vacuously pass.
        assertTrue(
            "Unit tests must run on a variant with ALLOW_QA_LAUNCH_CONFIG=true",
            BuildConfig.ALLOW_QA_LAUNCH_CONFIG
        )

        prefs = mockk(relaxed = true)
        snodeDirectory = mockk(relaxed = true)
    }

    private fun apply(value: String?) {
        val intent = Intent().putExtra("sessionUnrelatedMarker", "1")
        if (value != null) intent.putExtra("sessionPro", value)
        QaLaunchConfig.apply(intent, prefs, snodeDirectory)
    }

    @Test
    fun `true and 1 turn Pro on`() {
        apply("true")
        apply("1")

        verify(exactly = 2) { prefs.setForcePostPro(true) }
    }

    @Test
    fun `false and 0 turn Pro off`() {
        apply("false")
        apply("0")

        verify(exactly = 2) { prefs.setForcePostPro(false) }
    }

    @Test
    fun `an unknown value or no extra leaves the setting alone`() {
        apply("yes")
        apply(null)

        verify(exactly = 0) { prefs.setForcePostPro(any()) }
    }
}
