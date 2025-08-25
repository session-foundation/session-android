package org.thoughtcrime.securesms

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.BeforeClass
import org.junit.Rule
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.util.MockLoggingRule

open class BaseViewModelTest: BaseCoroutineTest() {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    var mockLoggingRule = MockLoggingRule()

}