package org.thoughtcrime.securesms

import org.junit.After
import org.junit.Before
import org.mockito.MockedStatic
import org.mockito.Mockito.any
import org.mockito.Mockito.mockStatic
import org.session.libsignal.utilities.Log

abstract class LogMockingTestBase {
    private lateinit var logMock: MockedStatic<Log>

    @Before
    fun mockLog() {
        logMock = mockStatic(Log::class.java).apply {
            // Stub the ones you hit. PathManager uses Log.w + Log.d.
            `when`<Unit> { Log.w(any(), any<String>()) }.then { }
            `when`<Unit> { Log.w(any(), any<String>(), any()) }.then { }
            `when`<Unit> { Log.d(any(), any<String>()) }.then { }
            `when`<Unit> { Log.d(any(), any<String>(), any()) }.then { }
            `when`<Unit> { Log.e(any(), any<String>()) }.then { }
            `when`<Unit> { Log.e(any(), any<String>(), any()) }.then { }
        }
    }

    @After
    fun unmockLog() {
        logMock.close()
    }
}
