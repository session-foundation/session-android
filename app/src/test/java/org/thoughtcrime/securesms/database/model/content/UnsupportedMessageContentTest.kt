package org.thoughtcrime.securesms.database.model.content

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UnsupportedMessageContentTest {
    private val json = Json {
        serializersModule = MessageContentModule().provideMessageContentSerializersModule()
    }

    @Test
    fun `round trips through the message_content column format`() {
        val encoded = json.encodeToString<MessageContent>(UnsupportedMessageContent)

        assertEquals("""{"type":"unsupported_message"}""", encoded)
        assertEquals(UnsupportedMessageContent, json.decodeFromString<MessageContent>(encoded))
    }
}
