package network.loki.messenger

import android.Manifest
import android.app.Instrumentation
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.adevinta.android.barista.interaction.PermissionGranter
import com.bumptech.glide.Glide
import network.loki.messenger.util.InputBarButtonDrawableMatcher.Companion.inputButtonWithDrawable
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.utilities.TextSecurePreferences
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.conversation.v2.input_bar.InputBar
import org.thoughtcrime.securesms.home.HomeActivity

/**
 * Currently not used as part of our CI/Deployment processes !!!!
 */

@RunWith(AndroidJUnit4::class)
@LargeTest
class HomeActivityTests {

    @get:Rule
    var activityRule = ActivityScenarioRule(HomeActivity::class.java)

    private val activityMonitor = Instrumentation.ActivityMonitor(ConversationActivityV2::class.java.name, null, false)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().addMonitor(activityMonitor)
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(activityMonitor)
    }

    private fun sendMessage(messageToSend: String, linkPreview: LinkPreview? = null) {
        // assume in chat activity
        onView(allOf(isDescendantOfA(withId(R.id.inputBar)),withId(R.id.inputBarEditText))).perform(ViewActions.replaceText(messageToSend))
        if (linkPreview != null) {
            val activity = activityMonitor.waitForActivity() as ConversationActivityV2
            val glide = Glide.with(activity)
            activity.findViewById<InputBar>(R.id.inputBar).updateLinkPreviewDraft(glide, linkPreview)
        }
        onView(allOf(isDescendantOfA(withId(R.id.inputBar)),inputButtonWithDrawable(R.drawable.ic_arrow_up))).perform(ViewActions.click())
        // TODO: text can flaky on cursor reload, figure out a better way to wait for the UI to settle with new data
        onView(isRoot()).perform(waitFor(500))
    }

    private fun objectFromDesc(id: Int) = device.findObject(By.desc(context.getString(id)))

    private fun setupLoggedInState(hasViewedSeed: Boolean = false) {
        // landing activity
        objectFromDesc(R.string.onboardingAccountCreate).click()

        // display name selection
        objectFromDesc(R.string.displayNameEnter).click()
        device.pressKeyCode(65)
        device.pressKeyCode(66)
        device.pressKeyCode(67)

        // Continue with display name
        objectFromDesc(R.string.theContinue).click()

        // Continue with default push notification setting
        objectFromDesc(R.string.theContinue).click()

        // PN select
        if (hasViewedSeed) {
            // has viewed seed is set to false after register activity
            TextSecurePreferences.setHasViewedSeed(InstrumentationRegistry.getInstrumentation().targetContext, true)
        }
        // allow notification permission
        PermissionGranter.allowPermissionsIfNeeded(Manifest.permission.POST_NOTIFICATIONS)
    }

/*    private fun goToMyChat() {
        onView(withId(R.id.newConversationButton)).perform(ViewActions.click())
        onView(withId(R.id.createPrivateChatButton)).perform(ViewActions.click())
        // new chat
        onView(withId(R.id.publicKeyEditText)).perform(ViewActions.closeSoftKeyboard())
        onView(withId(R.id.copyButton)).perform(ViewActions.click())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var copied: String
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            copied = clipboardManager.primaryClip!!.getItemAt(0).text.toString()
        }
        onView(withId(R.id.publicKeyEditText)).perform(ViewActions.typeText(copied))
        onView(withId(R.id.publicKeyEditText)).perform(ViewActions.closeSoftKeyboard())
        onView(withId(R.id.createPrivateChatButton)).perform(ViewActions.click())
    }*/

    @Test
    fun testLaunches_dismiss_seedView() {
        setupLoggedInState()
        objectFromDesc(R.string.theContinue).click()
        objectFromDesc(R.string.copy).click()
        pressBack()
        onView(withId(R.id.seedReminderView)).check(matches(not(isDisplayed())))
    }

    @Test
    fun testIsVisible_seedView() {
        setupLoggedInState()
        onView(withId(R.id.seedReminderView)).check(matches(isCompletelyDisplayed()))
    }

    @Test
    fun testIsVisible_alreadyDismissed_seedView() {
        setupLoggedInState(hasViewedSeed = true)
        onView(withId(R.id.seedReminderView)).check(matches(not(isDisplayed())))
    }

/*    @Test
    fun testChat_withSelf() {
        setupLoggedInState()
        goToMyChat()
        TextSecurePreferences.setLinkPreviewsEnabled(context, true)
        sendMessage("howdy")
        sendMessage("test")
        // tests url rewriter doesn't crash
        sendMessage("https://www.getsession.org?random_query_parameter=testtesttesttesttesttesttesttest&other_query_parameter=testtesttesttesttesttesttesttest")
        sendMessage("https://www.ámazon.com")
    }

    @Test
    fun testChat_displaysCorrectUrl() {
        setupLoggedInState()
        goToMyChat()
        TextSecurePreferences.setLinkPreviewsEnabled(InstrumentationRegistry.getInstrumentation().targetContext, true)
        // given the link url text
        val url = "https://www.ámazon.com"
        sendMessage(url, LinkPreview(url, "amazon", Optional.absent()))

        // when the URL span is clicked
        onView(withSubstring(url)).perform(ViewActions.click())

        // then the URL dialog should be displayed with a known punycode url
        val amazonPuny = "https://www.xn--mazon-wqa.com/"

        val dialogPromptText = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.dialog_open_url_explanation, amazonPuny)

        onView(isRoot()).perform(waitFor(1000)) // no other way for this to work apparently
        onView(withText(dialogPromptText)).check(matches(isDisplayed()))
    }*/


    /**
     * Perform action of waiting for a specific time.
     */
    fun waitFor(millis: Long): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View>? {
                return isRoot()
            }

            override fun getDescription(): String = "Wait for $millis milliseconds."

            override fun perform(uiController: UiController, view: View?) {
                uiController.loopMainThreadForAtLeast(millis)
            }
        }
    }
}