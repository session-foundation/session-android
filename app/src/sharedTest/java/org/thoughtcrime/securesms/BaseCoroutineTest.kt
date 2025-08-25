package org.thoughtcrime.securesms

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

open class BaseCoroutineTest {

    protected fun runBlockingTest(test: suspend TestScope.() -> Unit) = runTest {
        test()
    }
}