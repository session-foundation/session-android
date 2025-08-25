package org.session.libsession.messaging.jobs

import org.session.libsession.messaging.utilities.Data
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionJobInstantiator @Inject constructor(factories: SessionJobManagerFactories) {
    private val jobFactories by lazy { factories.getSessionJobFactories() }

    fun instantiate(jobFactoryKey: String, data: Data): Job? {
        if (jobFactories.containsKey(jobFactoryKey)) {
            return jobFactories[jobFactoryKey]?.create(data)
        } else {
            return null
        }
    }
}