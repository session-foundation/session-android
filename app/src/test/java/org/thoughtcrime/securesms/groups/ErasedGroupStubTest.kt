package org.thoughtcrime.securesms.groups

import com.google.common.truth.Truth.assertThat
import network.loki.messenger.libsession_util.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.Bytes
import network.loki.messenger.libsession_util.util.GroupInfo
import org.junit.Test

class ErasedGroupStubTest {
    private val stub = GroupInfo.ClosedGroupInfo(
        groupAccountId = "03" + "ab".repeat(32),
        adminKey = null,
        authData = null,
        priority = PRIORITY_VISIBLE,
        invited = false,
        name = "",
        destroyed = true,
        joinedAtSecs = 0L,
        kicked = false,
    )

    @Test
    fun `a removed entry with no name and no keys is an erased-group stub`() {
        assertThat(stub.isErasedGroupStub()).isTrue()
        assertThat(stub.copy(destroyed = false, kicked = true).isErasedGroupStub()).isTrue()
    }

    @Test
    fun `a destroyed group we were in keeps its name and is not a stub`() {
        assertThat(stub.copy(name = "Book club").isErasedGroupStub()).isFalse()
    }

    @Test
    fun `an entry with an admin key or auth data is not a stub`() {
        assertThat(stub.copy(adminKey = Bytes(ByteArray(64) { 1 })).isErasedGroupStub()).isFalse()
        assertThat(stub.copy(authData = Bytes(ByteArray(100) { 7 })).isErasedGroupStub()).isFalse()
    }

    @Test
    fun `a group still in use is never a stub, even without a name`() {
        assertThat(stub.copy(destroyed = false).isErasedGroupStub()).isFalse()
    }
}
