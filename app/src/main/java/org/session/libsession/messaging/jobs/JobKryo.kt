package org.session.libsession.messaging.jobs

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.google.protobuf.MessageLite

/**
 * The [Kryo] instance used to persist jobs, and to read them back after a restart.
 *
 * Every job that persists a message must use this rather than a bare [Kryo]: the two directions
 * have to agree on the serializers, and a job that writes with one configuration and reads with
 * another is silently dropped on restart rather than failing at the point of the mistake.
 */
internal fun jobKryo(): Kryo = Kryo().apply {
    isRegistrationRequired = false
    addDefaultSerializer(MessageLite::class.java, ProtobufSerializer())
}

/**
 * Kryo builds objects field by field, and parts of a protobuf message's object graph have no
 * constructor it can call — so it writes one happily and then throws ("Class cannot be created") on
 * the way back in, taking the job that held it with it. Persist the wire format the message can
 * rebuild itself from instead.
 */
private class ProtobufSerializer : Serializer<MessageLite>() {
    override fun write(kryo: Kryo, output: Output, message: MessageLite) {
        val bytes = message.toByteArray()
        output.writeVarInt(bytes.size, true)
        output.writeBytes(bytes)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out MessageLite>): MessageLite {
        val bytes = input.readBytes(input.readVarInt(true))

        return type.getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes) as MessageLite
    }
}
