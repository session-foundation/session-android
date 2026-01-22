package org.thoughtcrime.securesms.api.http

import okhttp3.Request
import okhttp3.Response
import org.thoughtcrime.securesms.api.ApiExecutor

typealias HttpApiExecutor = ApiExecutor<Request, Response>