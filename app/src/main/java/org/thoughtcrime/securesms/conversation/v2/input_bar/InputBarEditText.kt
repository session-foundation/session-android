package org.thoughtcrime.securesms.conversation.v2.input_bar

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import org.thoughtcrime.securesms.util.toPx
import kotlin.math.roundToInt

class InputBarEditText : AppCompatEditText {
    private val screenWidth get() = Resources.getSystem().displayMetrics.widthPixels
    var delegate: InputBarEditTextDelegate? = null

    var allowMultimediaInput: Boolean = true

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onTextChanged(text: CharSequence, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        delegate?.inputBarEditTextContentChanged(text)

        // - A "chunk" got inserted (lengthAfter >= 3)
        // - If it is large enough, treat it as paste. We do this because some IME's clipboard history
        //   will not be treated as a "paste" but instead just a normal text insertion
        if (lengthAfter >= 3) { // catch most real paste
            val inserted = safeSubSequence(text, start, start + lengthAfter)
            if (!inserted.isNullOrEmpty()) {
                // Bulk insert will mostly come from IME that supports clipboard history
                val isBulkInsert = inserted.length >= 5 // small enough to catch URIs

                if (isBulkInsert) {
                    delegate?.onPaste()
                }
            }
        }

        // Calculate the width manually to get it right even before layout has happened (i.e.
        // when restoring a draft). The 64 DP is the horizontal margin around the input bar
        // edit text.
        val width = (screenWidth - 2 * toPx(64.0f, resources)).roundToInt()
        if (width < 0) { return } // screenWidth initially evaluates to 0
    }

    // Small helper to avoid IndexOutOfBounds on weird IME behavior
    private fun safeSubSequence(text: CharSequence, start: Int, end: Int): String? {
        if (start < 0 || end > text.length || start >= end) return null
        return text.subSequence(start, end).toString()
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo) ?: return null
        EditorInfoCompat.setContentMimeTypes(editorInfo,
            if (allowMultimediaInput) arrayOf("image/png", "image/gif", "image/jpg") else null
        )

        val callback =
                InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, opts ->
                    val lacksPermission = (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
                    // read and display inputContentInfo asynchronously
                    if (lacksPermission) {
                        try {
                            inputContentInfo.requestPermission()
                        } catch (e: Exception) {
                            return@OnCommitContentListener false // return false if failed
                        }
                    }

                    inputContentInfo.contentUri

                    // read and display inputContentInfo asynchronously.
                    delegate?.commitInputContent(inputContentInfo.contentUri)

                    true // return true if succeeded
                }



        return InputConnectionCompat.createWrapper(ic, editorInfo, callback)
    }
}

interface InputBarEditTextDelegate {
    fun inputBarEditTextContentChanged(text: CharSequence)
    fun commitInputContent(contentUri: Uri)
    fun onPaste()
}