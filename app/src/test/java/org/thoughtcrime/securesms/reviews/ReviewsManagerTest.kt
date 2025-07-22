package org.thoughtcrime.securesms.reviews

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.thoughtcrime.securesms.util.MockLoggingRule
import java.util.EnumSet

@RunWith(JUnit4::class)
class ReviewsManagerTest {


    @get:Rule
    val mockLoggingRule = MockLoggingRule()


    private fun TestScope.createManager(
        isFreshInstall: Boolean,
        supportInAppReviewFlow: Boolean = true
    ): ReviewsManager {
        val pm = mock<PackageManager> {
            on { getPackageInfo(any<String>(), any<Int>()) } doReturn PackageInfo().apply {
                if (isFreshInstall) {
                    firstInstallTime = System.currentTimeMillis()
                    lastUpdateTime = firstInstallTime
                } else {
                    firstInstallTime = System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 30 // 30 days ago
                    lastUpdateTime = System.currentTimeMillis() // Just now
                }
            }
        }

        var reviewState: String? = null

        return ReviewsManager(
            context = mock {
                on { packageManager } doReturn pm
                on { packageName } doReturn "mypackage.name"
            },
            prefs = mock {
                on { inAppReviewState } doAnswer { reviewState }
                on { inAppReviewState = any() } doAnswer { reviewState = it.arguments[0] as? String }
            },
            json = Json {
                serializersModule += ReviewsSerializerModule().provideReviewsSerializersModule()
            },
            clock = mock {
                on { currentTimeMills() } doAnswer { System.currentTimeMillis() }
            },
            storeReviewManager = mock {
                on { supportsReviewFlow } doReturn supportInAppReviewFlow
            },
            scope = backgroundScope,
        )
    }

    @Test
    fun `should show prompt on triggers on fresh install`() = runTest {

        for (event in listOf(ReviewsManager.Event.ThemeChanged,
            ReviewsManager.Event.PathScreenVisited,
            ReviewsManager.Event.DonateButtonPressed)) {
            val manager = createManager(isFreshInstall = true)

            manager.shouldShowPrompt.test {
                assertFalse(awaitItem()) // Initially should not show prompt

                manager.onEvent(event) // Send the event

                assertTrue(awaitItem()) // Should show prompt after the event
            }
        }
    }

    @Test
    fun `should show prompt respectively on triggers on update`() = runTest {
        val shouldShowByEvent = mapOf(
            ReviewsManager.Event.ThemeChanged to false,
            ReviewsManager.Event.PathScreenVisited to false,
            ReviewsManager.Event.DonateButtonPressed to true
        )

        for ((event, shouldShow) in shouldShowByEvent) {
            val manager = createManager(isFreshInstall = false)

            manager.shouldShowPrompt.test {
                assertFalse(awaitItem()) // Initially should not show prompt
                manager.onEvent(event) // Send the event
                if (shouldShow) {
                    assertTrue(awaitItem())
                } else {
                    expectNoEvents()
                }
            }
        }
    }

    @Test
    fun `should schedule prompt if abandoned mid-flow`() = runTest {
        val manager = createManager(isFreshInstall = true)
        manager.shouldShowPrompt.test {
            assertFalse(awaitItem()) // Initially should not show prompt

            manager.onEvent(ReviewsManager.Event.DonateButtonPressed) // Send the event
            assertTrue(awaitItem()) // Should show prompt

            manager.onEvent(ReviewsManager.Event.ReviewFlowAbandoned) // User abandons the flow
            assertFalse(awaitItem()) // The prompt should be gone now

            advanceTimeBy(ReviewsManager.REVIEW_REQUEST_DISMISS_DELAY)

            assertTrue(awaitItem()) // Should show prompt after the delay

            manager.onEvent(ReviewsManager.Event.ReviewFlowAbandoned) // User abandons the flow again
            assertFalse(awaitItem()) // The prompt should be gone now
            expectNoEvents()
        }
    }

    @Test
    fun `dismiss should work for first time showing review request`() = runTest {
        val manager = createManager(isFreshInstall = true)
        manager.shouldShowPrompt.test {
            assertFalse(awaitItem()) // Initially should not show prompt

            manager.onEvent(ReviewsManager.Event.DonateButtonPressed) // Send the event
            assertTrue(awaitItem()) // Should show prompt

            manager.onEvent(ReviewsManager.Event.Dismiss) // User dismisses the prompt
            assertFalse(awaitItem()) // The prompt should be gone now
            expectNoEvents()
        }
    }

    @Test
    fun `dismiss should work for delay-dismissed review request`() = runTest {
        val manager = createManager(isFreshInstall = true)
        manager.shouldShowPrompt.test {
            assertFalse(awaitItem()) // Initially should not show prompt

            manager.onEvent(ReviewsManager.Event.DonateButtonPressed) // Send the event
            assertTrue(awaitItem()) // Should show prompt

            manager.onEvent(ReviewsManager.Event.ReviewFlowAbandoned) // User abandons the flow
            assertFalse(awaitItem()) // The prompt should be gone now

            advanceTimeBy(ReviewsManager.REVIEW_REQUEST_DISMISS_DELAY)

            assertTrue(awaitItem()) // Should show prompt after the delay

            manager.onEvent(ReviewsManager.Event.Dismiss) // User abandons the flow again
            assertFalse(awaitItem()) // The prompt should be gone now
            expectNoEvents()
        }
    }

    @Test
    fun `review request should not show again once dismissed`() = runTest {
        val allEvents = EnumSet.allOf(ReviewsManager.Event::class.java)

        for (triggerEvent in allEvents) {
            val manager = createManager(isFreshInstall = true)
            manager.shouldShowPrompt.test {
                assertFalse(awaitItem()) // Initially should not show prompt

                manager.onEvent(ReviewsManager.Event.DonateButtonPressed) // Send the event
                assertTrue(awaitItem()) // Should show prompt

                manager.onEvent(ReviewsManager.Event.Dismiss) // User dismisses the prompt
                assertFalse(awaitItem()) // The prompt should be gone now

                // Try the trigger event now
                manager.onEvent(triggerEvent)
                expectNoEvents()
            }
        }
    }

    @Test
    fun `should never show when in app flow is not supported`() = runTest {
        val allEvents = EnumSet.allOf(ReviewsManager.Event::class.java)

        for (triggerEvent in allEvents) {
            val manager = createManager(isFreshInstall = true, supportInAppReviewFlow = false)
            manager.shouldShowPrompt.test {
                assertFalse(awaitItem()) // Initially should not show prompt

                manager.onEvent(triggerEvent) // Send the event
                expectNoEvents()
            }
        }
    }
}