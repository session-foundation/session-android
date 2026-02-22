package org.thoughtcrime.securesms.util.adapter

import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView


fun RecyclerView.runWhenLaidOut(block: () -> Unit) {
    if (isLaidOut && !isLayoutRequested) {
        post(block)
    } else {
        doOnPreDraw { post(block) }
    }
}