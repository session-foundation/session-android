package org.thoughtcrime.securesms

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.TaskStackBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.dependencies.DatabaseComponent
import org.thoughtcrime.securesms.home.HomeActivity

class ShortcutLauncherActivity : AppCompatActivity() {
    @SuppressLint("StaticFieldLeak")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serializedAddress = intent.getStringExtra(KEY_SERIALIZED_ADDRESS)

        if (serializedAddress == null) {
            Toast.makeText(this, R.string.invalidShortcut, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val backStack = TaskStackBuilder.create(this)
            .addNextIntent(Intent(this, HomeActivity::class.java))

        // start the appropriate conversation activity and finish this one
        lifecycleScope.launch(Dispatchers.Default) {
            val context = this@ShortcutLauncherActivity

            val address = fromSerialized(serializedAddress)
            val threadId = DatabaseComponent.get(context).threadDatabase().getOrCreateThreadIdFor(address)

            val intent = Intent(context, ConversationActivityV2::class.java)
            intent.putExtra(ConversationActivityV2.ADDRESS, address)
            intent.putExtra(ConversationActivityV2.THREAD_ID, threadId)

            backStack.addNextIntent(intent)
            backStack.startActivities()
            finish()
        }
    }

    companion object {
        private const val KEY_SERIALIZED_ADDRESS = "serialized_address"

        fun createIntent(context: Context, address: Address): Intent {
            val intent = Intent(context, ShortcutLauncherActivity::class.java)
            intent.setAction(Intent.ACTION_MAIN)
            intent.putExtra(KEY_SERIALIZED_ADDRESS, address.toString())

            return intent
        }
    }
}
