package org.thoughtcrime.securesms.contacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import androidx.recyclerview.widget.LinearLayoutManager
import network.loki.messenger.R
import network.loki.messenger.databinding.ActivitySelectContactsBinding
import org.thoughtcrime.securesms.ScreenLockActionBarActivity
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SelectContactsToInviteToGroupActivity : ScreenLockActionBarActivity(), LoaderManager.LoaderCallbacks<List<String>> {
    private lateinit var binding: ActivitySelectContactsBinding

    private var members = listOf<String>()
        set(value) { field = value; selectContactsAdapter.members = value }

    private lateinit var usersToExclude: Set<String>

    private val selectContactsAdapter by lazy {
        SelectContactsAdapter(this, Glide.with(this))
    }

    companion object {
        const val USERS_TO_EXCLUDE_KEY  = "usersToExcludeKey"
        const val EMPTY_STATE_TEXT_KEY  = "emptyStateTextKey"
        const val SELECTED_CONTACTS_KEY = "selectedContactsKey"
    }

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        binding = ActivitySelectContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar!!.title = resources.getString(R.string.membersInvite)

        usersToExclude = intent.getStringArrayExtra(USERS_TO_EXCLUDE_KEY)?.toSet() ?: setOf()
        val emptyStateText = intent.getStringExtra(EMPTY_STATE_TEXT_KEY)
        if (emptyStateText != null) {
            binding.emptyStateMessageTextView.text = emptyStateText
        }

        binding.recyclerView.adapter = selectContactsAdapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_done, menu)
        return members.isNotEmpty()
    }
    // endregion

    // region Updating
    override fun onCreateLoader(id: Int, bundle: Bundle?): Loader<List<String>> {
        return SelectContactsLoader(this, usersToExclude)
    }

    override fun onLoadFinished(loader: Loader<List<String>>, members: List<String>) {
        update(members)
    }

    override fun onLoaderReset(loader: Loader<List<String>>) {
        update(listOf())
    }

    private fun update(members: List<String>) {
        this.members = members
        binding.recyclerView.visibility = if (members.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyStateContainer.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }
    // endregion

    // region Interaction
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.doneButton -> closeAndReturnSelected()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun closeAndReturnSelected() {
        val selectedMembers = selectContactsAdapter.selectedMembers
        val selectedContacts = selectedMembers.toTypedArray()
        val intent = Intent()
        intent.putExtra(SELECTED_CONTACTS_KEY, selectedContacts)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
    // endregion
}