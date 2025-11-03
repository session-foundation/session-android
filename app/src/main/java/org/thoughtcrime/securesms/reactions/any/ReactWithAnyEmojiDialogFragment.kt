package org.thoughtcrime.securesms.reactions.any

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.fragment.app.DialogFragment
import androidx.loader.app.LoaderManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import network.loki.messenger.R
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord

class ReactWithAnyEmojiDialogFragment : BottomSheetDialogFragment() {
    private var callback: Callback? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        callback =
            if (parentFragment is Callback) {
                parentFragment as Callback
            } else {
                context as Callback
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_Session_BottomSheet)
        dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.8).toInt()

        // dialog.getBehavior().setDraggable(false);
        val shadows = requireArguments().getBoolean(ARG_SHADOWS, true)
        if (!shadows) {
            val window = dialog.window
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.react_with_any_emoji_dialog_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (view.findViewById<View>(R.id.emoji_picker) as EmojiPickerView).setOnEmojiPickedListener{
            onEmojiSelected(it.emoji)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LoaderManager.getInstance(requireActivity()).destroyLoader(
            requireArguments().getLong(
                ARG_MESSAGE_ID
            ).toInt()
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        callback!!.onReactWithAnyEmojiDialogDismissed()
    }

    fun onEmojiSelected(emoji: String) {
        val args = requireArguments()
        val messageId = MessageId(args.getLong(ARG_MESSAGE_ID), args.getBoolean(ARG_IS_MMS))
        callback!!.onReactWithAnyEmojiSelected(emoji, messageId)
        dismiss()
    }

    interface Callback {
        fun onReactWithAnyEmojiDialogDismissed()

        fun onReactWithAnyEmojiSelected(emoji: String, messageId: MessageId)
    }

    companion object {
        private const val ARG_MESSAGE_ID = "arg_message_id"
        private const val ARG_IS_MMS = "arg_is_mms"
        private const val ARG_START_PAGE = "arg_start_page"
        private const val ARG_SHADOWS = "arg_shadows"

        fun createForMessageRecord(
            messageRecord: MessageRecord,
            startingPage: Int
        ): DialogFragment {
            val fragment: DialogFragment = ReactWithAnyEmojiDialogFragment()
            val args = Bundle()

            args.putLong(ARG_MESSAGE_ID, messageRecord.getId())
            args.putBoolean(ARG_IS_MMS, messageRecord.isMms)
            args.putInt(ARG_START_PAGE, startingPage)
            fragment.arguments = args

            return fragment
        }
    }
}
