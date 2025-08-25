package org.thoughtcrime.securesms.dependencies

import javax.inject.Qualifier

@Retention(AnnotationRetention.SOURCE)
@Qualifier
@Target(
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR
)
annotation class ManagerScope