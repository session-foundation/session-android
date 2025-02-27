package org.thoughtcrime.securesms.home.search

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.RelativeLayout.LayoutParams
import android.widget.RelativeLayout.inflate
import androidx.annotation.StringRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import androidx.compose.ui.unit.dp
import androidx.core.view.get
import androidx.core.view.size
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewGlobalSearchHeaderBinding
import network.loki.messenger.databinding.ViewGlobalSearchResultBinding
import network.loki.messenger.databinding.ViewGlobalSearchSubheaderBinding
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.ThemeUtil
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.search.model.MessageResult
import org.thoughtcrime.securesms.ui.GetString
import java.security.InvalidParameterException
import org.session.libsession.messaging.contacts.Contact as ContactModel

class GlobalSearchAdapter(val context: Context, private val modelCallback: (Model)->Unit): RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val HEADER_VIEW_TYPE = 0
        const val SUB_HEADER_VIEW_TYPE = 1
        const val CONTENT_VIEW_TYPE = 2
    }

    private var data: List<Model> = listOf()
    private var query: String? = null

    fun setNewData(data: Pair<String, List<Model>>) = setNewData(data.first, data.second)

    fun setNewData(query: String, newData: List<Model>) {
        val diffResult = DiffUtil.calculateDiff(GlobalSearchDiff(this.query, query, data, newData))
        this.query = query
        data = newData
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int =
        when (data[position]) {
            is Model.Header -> HEADER_VIEW_TYPE
            is Model.SubHeader -> SUB_HEADER_VIEW_TYPE
            else -> CONTENT_VIEW_TYPE
        }

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            HEADER_VIEW_TYPE -> HeaderView(
                LayoutInflater.from(parent.context).inflate(R.layout.view_global_search_header, parent, false)
            )
            SUB_HEADER_VIEW_TYPE -> SubHeaderView(
                LayoutInflater.from(parent.context).inflate(R.layout.view_global_search_subheader, parent, false)
            )
            else -> ContentView(
                LayoutInflater.from(parent.context).inflate(R.layout.view_global_search_result, parent, false),
                modelCallback
            )
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val newUpdateQuery: String? = payloads.firstOrNull { it is String } as String?
        if (newUpdateQuery != null && holder is ContentView) {
            holder.bindPayload(newUpdateQuery, data[position])
            return
        }
        when (holder) {
            is HeaderView -> holder.bind(data[position] as Model.Header)
            is SubHeaderView -> holder.bind(data[position] as Model.SubHeader)
            is ContentView -> holder.bind(query.orEmpty(), data[position])
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder,position, mutableListOf())
    }

    class HeaderView(view: View) : RecyclerView.ViewHolder(view) {
        val binding = ViewGlobalSearchHeaderBinding.bind(view)
        fun bind(header: Model.Header) {
            binding.searchHeader.text = header.title.string(binding.root.context)
        }
    }

    class SubHeaderView(view: View) : RecyclerView.ViewHolder(view) {

        val binding = ViewGlobalSearchSubheaderBinding.bind(view)

        fun bind(header: Model.SubHeader) {
            binding.searchHeader.text = header.title.string(binding.root.context)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ContentView) {
            holder.binding.searchResultProfilePicture.recycle()
        }
    }

    // Note: We mark the ContentView as an inner class in order to access the context from the GlobalSearchAdapter
    inner class ContentView(view: View, private val modelCallback: (Model) -> Unit) : RecyclerView.ViewHolder(view) {

        val binding = ViewGlobalSearchResultBinding.bind(view)

        fun bindPayload(newQuery: String, model: Model) {
            bindQuery(newQuery, model)
        }

        @SuppressLint("RestrictedApi")
        private fun PopupMenu.forceShowIcons() {
            try {
                val fields = this.javaClass.declaredFields
                for (field in fields) {
                    if ("mPopup" == field.name) {
                        field.isAccessible = true
                        val menuPopupHelper = field.get(this) as MenuPopupHelper
                        val classPopupHelper = Class.forName(menuPopupHelper.javaClass.name)
                        val setForceIconsMethod = classPopupHelper.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                        setForceIconsMethod.invoke(menuPopupHelper, true)

                        // Add horiz. offset
//                        val offset = binding.searchResultProfilePicture.width / 2
//                        val horizOffsetMethod = menuPopupHelper.javaClass.getMethod("setHorizontalOffset", Int::class.javaPrimitiveType)
//                        horizOffsetMethod.invoke(menuPopupHelper, offset)
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun showContactActionsPopupMenu() {

            val wrapper = ContextThemeWrapper(context, R.style.ContactPopupMenuStyle)

            val origLeftMargin = binding.searchResultProfilePicture.left
            val origTopMargin = binding.searchResultProfilePicture.top

            Log.w("ACL", "Left is: " + origLeftMargin + ", top is: " + origTopMargin)

            val offsetView = View(context)
            val tempParams = binding.searchResultProfilePicture.layoutParams as FrameLayout.LayoutParams

            val params = LayoutParams(tempParams.width, tempParams.height).apply {
                leftMargin = origLeftMargin + 250
                topMargin = origTopMargin + 100
            }
            //params.leftMargin = origLeftMargin + 250
            //params.topMargin = topMargin - 150
            offsetView.layoutParams = params
            binding.root.addView(offsetView)

            // Attach the offsetView to the same parent as binding.searchResultProfilePicture.
//            val parentLayout = binding.searchResultProfilePicture.parent as? ViewGroup
//            if (parentLayout != null) {
//                parentLayout.addView(offsetView)
//            } else {
//                Log.e("ACL", "Parent layout is not a ViewGroup; cannot attach offset view.")
//            }

            val popupMenu = PopupMenu(wrapper, binding.searchResultTitle) // This offset view is meant to create the pop-up menu across and up a bit

            popupMenu.menuInflater.inflate(R.menu.menu_contact_actions, popupMenu.menu)

            // Menu icons are not shown by default - force them on
            popupMenu.forceShowIcons()

            // Now tint each icon
            val redColor = ThemeUtil.getThemedColor(context, R.attr.danger)
            for (i in 0 until popupMenu.menu.size) {
                popupMenu.menu[i].icon?.setTint(redColor)
            }

            // Handle menu item clicks:
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_block -> {
                        Log.w("ACL", "Asked to block!")
                        true
                    }
                    R.id.action_delete -> {
                        Log.w("ACL", "Asked to delete!")
                        true
                    }
                    else -> false
                }
            }

            // Finally show the popup
            popupMenu.show()

            // Finally, show the popup menu with custom positioning using Gravity
//            val gravity = Gravity.TOP or Gravity.START // Adjust the gravity as needed
//            PopupWindow(popupMenu.menu.toMenuView(), WRAP_CONTENT, WRAP_CONTENT, true).apply {
//                showAtLocation(binding.searchResultProfilePicture, gravity, leftMargin + 50, topMargin - 50)
//            }


            //val rawView = popupMenu as View

            // Calculate offset: half the anchor view's width
            val offset = binding.searchResultProfilePicture.width / 2

//            try {
//                // First, get the MenuPopupHelper instance from the PopupMenu via reflection
//                val mPopupField = PopupMenu::class.java.getDeclaredField("mPopup")
//                mPopupField.isAccessible = true
//                val popupMenu = mPopupField.get(popupMenu)

                // Now, iterate over the fields of menuPopupHelper to find the ListPopupWindow or related type
//                val fields = menuPopupHelper.javaClass.declaredFields
//                for (field in fields) {
//                    Log.w("ACL", "Looking at field: ${field.name}, type: ${field.type.canonicalName}")
//
//                    // Check if the field's type is ListPopupWindow or any subclass (isAssignableFrom for inheritance)
//                    if (androidx.appcompat.widget.ListPopupWindow::class.java.isAssignableFrom(field.type)) {
//                        Log.w("ACL", "Setting offset")
//                        field.isAccessible = true
//                        val listPopupWindow = field.get(menuPopupHelper) as? androidx.appcompat.widget.ListPopupWindow
//                        listPopupWindow?.horizontalOffset = offset
//                        break
//                    } else if (androidx.appcompat.view.menu.MenuPopup::class.java.isAssignableFrom(field.type)) {
//                        Log.w("ACL", "Found MenuPopup field (potential candidate), checking further")
//                        // This indicates the field might be an instance of MenuPopup (which could be a ListPopupWindow)
//                        val menuPopup = field.get(menuPopupHelper) as? androidx.appcompat.view.menu.MenuPopup
//                        if (menuPopup is androidx.appcompat.widget.ListPopupWindow) {
//                            Log.w("ACL", "Found ListPopupWindow via MenuPopup!")
//                            menuPopup.horizontalOffset = offset
//                            break
//                        }
//                    }
//                }
//
//                Log.w("ACL", "Could not find field so set offset")

                // Now, iterate over the fields of menuPopupHelper to find the ListPopupWindow or related type
//                val fields = popupMenu.javaClass.declaredFields
//                for (field in fields) {
//                    Log.w("ACL", "Looking at field: ${field.name}, type: ${field.type.canonicalName}")
//
//                    // Dynamically check if the field's type is ListPopupWindow or any subclass using Class.forName
//                    try {
//                        // Check for ListPopupWindow class dynamically
//                        val listPopupWindowClass = Class.forName("androidx.appcompat.widget.ListPopupWindow")
//                        if (listPopupWindowClass.isAssignableFrom(field.type)) {
//                            Log.w("ACL", "Setting offset for ListPopupWindow field")
//                            field.isAccessible = true
//                            val listPopupWindow = field.get(popupMenu) as? androidx.appcompat.widget.ListPopupWindow
//                            listPopupWindow?.horizontalOffset = offset
//                            break
//                        }

                        // Check for MenuPopup class dynamically
//                        val menuPopupClass = Class.forName("androidx.appcompat.view.menu.MenuPopup")
//                        if (menuPopupClass.isAssignableFrom(field.type)) {
//                            Log.w("ACL", "Found MenuPopup field (potential candidate), checking further")
//                            val menuPopup = field.get(menuPopupHelper) as? androidx.appcompat.view.menu.MenuPopup
//                            if (menuPopup is androidx.appcompat.widget.ListPopupWindow) {
//                                Log.w("ACL", "Found ListPopupWindow via MenuPopup!")
//                                menuPopup.horizontalOffset = offset
//                                break
//                            }
//                        }
//
//                    } catch (e: ClassNotFoundException) {
//                        Log.e("ACL", "Class not found: ${e.message}")
//                    }
//                }
//
//                Log.w("ACL", "Could not find field so set offset")
//
//
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }

//            try {
//                // Access the internal MenuPopupHelper field ("mPopup") in PopupMenu
//                val fieldPopup = popupMenu.javaClass.getDeclaredField("mPopup")
//                fieldPopup.isAccessible = true
//                val menuPopupHelper = fieldPopup.get(popupMenu)
//
//                // Invoke setHorizontalOffset(int) on the MenuPopupHelper
//                val method = menuPopupHelper.javaClass.getMethod("setHorizontalOffset", Int::class.javaPrimitiveType)
//                method.invoke(menuPopupHelper, offset)
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }



//            try {
//                // Access the internal field "mPopup" of PopupMenu
//                val field = popupMenu.javaClass.getDeclaredField("mPopup")
//                field.isAccessible = true
//                val menuPopupHelper = field.get(popupMenu)
//                // Now access the PopupWindow inside the helper
//                val popupWindowField = menuPopupHelper.javaClass.getDeclaredField("mPopup")
//                popupWindowField.isAccessible = true
//                val popupWindow = popupWindowField.get(menuPopupHelper) as PopupWindow
//                // Set your custom background here
//                popupWindow.setBackgroundDrawable(
//                    ContextCompat.getDrawable(context, R.drawable.popup_box_shape)
//                )
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }

            // Definitely not
//            try {
//                // Get the internal MenuPopupHelper instance
//                val field = popupMenu.javaClass.getDeclaredField("mPopup")
//                field.isAccessible = true
//                val menuPopupHelper = field.get(popupMenu)
//
//                // Use reflection to call the setBackgroundDrawable method on it
//                val setBgMethod = menuPopupHelper.javaClass.getMethod("setBackgroundDrawable", Drawable::class.java)
//                setBgMethod.invoke(menuPopupHelper, ContextCompat.getDrawable(context, R.drawable.popup_box_shape))
//
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }

//            try {
//                // First, get the mPopup field from PopupMenu which is the MenuPopupHelper.
//                val fieldPopup = popupMenu.javaClass.getDeclaredField("mPopup")
//                fieldPopup.isAccessible = true
//                val menuPopupHelper = fieldPopup.get(popupMenu)
//
//                // Next, get the mPopup field from MenuPopupHelper, which should be a ListPopupWindow.
//                val fieldListPopup = menuPopupHelper.javaClass.getDeclaredField("mPopup")
//                fieldListPopup.isAccessible = true
//                val listPopupWindow = fieldListPopup.get(menuPopupHelper) as? androidx.appcompat.widget.ListPopupWindow
//
//                listPopupWindow?.setBackgroundDrawable(
//                    ContextCompat.getDrawable(context, R.drawable.popup_box_shape)
//                )
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }



//            // Delay a little to ensure the drop-down list is created
//            binding.searchResultProfilePicture.post {
//                try {
//                    // Get the MenuPopupHelper from PopupMenu
//                    val fieldPopup = popupMenu.javaClass.getDeclaredField("mPopup")
//                    fieldPopup.isAccessible = true
//                    val menuPopupHelper = fieldPopup.get(popupMenu)
//
//                    // Retrieve the ListView (named "mDropDownList" internally)
//                    val listField = menuPopupHelper.javaClass.getDeclaredField("mDropDownList")
//                    listField.isAccessible = true
//                    val listView = listField.get(menuPopupHelper) as? android.widget.ListView
//                    // Set your custom background drawable
//                    listView?.setBackgroundResource(R.drawable.popup_box_shape)
//                } catch (e: Exception) {
//                    e.printStackTrace()
//                }
//            }
        }

        fun bind(query: String, model: Model) {
            binding.searchResultProfilePicture.recycle()
            when (model) {
                is Model.GroupConversation -> bindModel(query, model)
                is Model.Contact -> bindModel(query, model)
                is Model.Message -> bindModel(query, model)
                is Model.SavedMessages -> bindModel(model)
                else -> throw InvalidParameterException("Can't display as ContentView")
            }
            binding.root.setOnClickListener { modelCallback(model) }

            // Display the block / delete UI on long-press of a contact which isn't us
            binding.root.setOnLongClickListener {
                if (model is Model.Contact && !model.isSelf) {
                    showContactActionsPopupMenu()
                }
                true
            }
        }
    }

    sealed class Model {
        data class Header(val title: GetString): Model() {
            constructor(@StringRes title: Int): this(GetString(title))
            constructor(title: String): this(GetString(title))
        }
        data class SubHeader(val title: GetString): Model() {
            constructor(@StringRes title: Int): this(GetString(title))
            constructor(title: String): this(GetString(title))
        }
        data class SavedMessages(val currentUserPublicKey: String): Model() // Note: "Note to Self" counts as SavedMessages rather than a Contact where `isSelf` is true.
        data class Contact(val contact: ContactModel, val name: String?, val isSelf: Boolean) : Model()
        data class GroupConversation(val groupRecord: GroupRecord) : Model()
        data class Message(val messageResult: MessageResult, val unread: Int, val isSelf: Boolean) : Model()
    }
}
