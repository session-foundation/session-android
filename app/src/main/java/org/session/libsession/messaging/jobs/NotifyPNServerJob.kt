package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.Job.Companion.MAX_BUFFER_SIZE_BYTES
import org.session.libsession.messaging.sending_receiving.notifications.Server
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.network.ServerClient
import org.session.libsession.network.onion.Version
import org.session.libsession.snode.SnodeMessage
import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.retryWithUniformInterval
import kotlin.coroutines.cancellation.CancellationException

class NotifyPNServerJob(val message: SnodeMessage) : Job {
    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0

    override val maxFailureCount: Int = 20

    private val serverClient: ServerClient by lazy {
        MessagingModuleConfiguration.shared.serverClient
    }

    companion object {
        val KEY: String = "NotifyPNServerJob"

        // Keys used for database storage
        private val MESSAGE_KEY = "message"
    }

    override suspend fun execute(dispatcherName: String) {
        val server = Server.LEGACY
        val parameters = mapOf("data" to message.data, "send_to" to message.recipient)
        val url = "${server.url}/notify"
        val body = RequestBody.create("application/json".toMediaType(), JsonUtil.toJson(parameters))
        val request = Request.Builder().url(url).post(body).build()

        try {
            // High-level application retry (4 attempts)
            retryWithUniformInterval(maxRetryCount = 4) {
                serverClient.send(
                    request = request,
                    serverBaseUrl = server.url,
                    x25519PublicKey = server.publicKey,
                    version = Version.V2
                )

                // todo ONION the old code was checking the status code on success and if it is null or 0 it would log it as a fail
                // the new structure however throws all non 200.299 status as an OnionError
            }

            handleSuccess(dispatcherName)

        } catch (e: Exception) {
            if (e is CancellationException) throw e

            Log.d("NotifyPNServerJob", "Couldn't notify PN server due to error: $e.")
            handleFailure(dispatcherName, e)
        }
    }

    private fun handleSuccess(dispatcherName: String) {
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handleFailure(dispatcherName: String, error: Exception) {
        delegate?.handleJobFailed(this, dispatcherName, error)
    }

    override fun serialize(): Data {
        val kryo = Kryo()
        kryo.isRegistrationRequired = false
        val serializedMessage = ByteArray(4096)
        val output = Output(serializedMessage, MAX_BUFFER_SIZE_BYTES)
        kryo.writeObject(output, message)
        output.close()
        return Data.Builder()
            .putByteArray(MESSAGE_KEY, serializedMessage)
            .build();
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    class DeserializeFactory : Job.DeserializeFactory<NotifyPNServerJob> {

        override fun create(data: Data): NotifyPNServerJob {
            val serializedMessage = data.getByteArray(MESSAGE_KEY)
            val kryo = Kryo()
            kryo.isRegistrationRequired = false
            val input = Input(serializedMessage)
            val message = kryo.readObject(input, SnodeMessage::class.java)
            input.close()
            return NotifyPNServerJob(message)
        }
    }
}