package org.thoughtcrime.securesms.api.http

import org.thoughtcrime.securesms.api.ApiExecutor

/**
 * An [ApiExecutor] for sending [HttpRequest]s.
 */
typealias HttpApiExecutor = ApiExecutor<HttpRequest, HttpResponse>
