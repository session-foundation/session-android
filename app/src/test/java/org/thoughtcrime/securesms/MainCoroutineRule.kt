package org.thoughtcrime.securesms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class MainCoroutineRule() :
    TestWatcher() {

    companion object {
        private val dispatcherSet = AtomicBoolean(false)
    }

    override fun starting(description: Description) {
        super.starting(description)

        // Set the main dispatcher to test dispatcher, however we shouldn't reset the main dispatcher
        // as some coroutine tasks spawn during the testing may try to resume on the main dispatcher,
        // which will cause an exception if it has been reset.
        // Right now there aren't good ways to force the coroutines run on other threads to behave
        // correctly everytime so we'll just keep the main dispatcher as the test dispatcher globally.
        if (dispatcherSet.compareAndSet(false, true)) {
            Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        }
    }
}
