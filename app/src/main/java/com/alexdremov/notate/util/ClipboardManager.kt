package com.alexdremov.notate.util

import com.alexdremov.notate.model.CanvasItem
import java.util.ArrayList

object ClipboardManager {
    private val copiedItems = ArrayList<CanvasItem>()

    fun copy(items: Collection<CanvasItem>) {
        copiedItems.clear()
        copiedItems.addAll(items)
    }

    fun getItems(): List<CanvasItem> = ArrayList(copiedItems)

    fun hasContent() = copiedItems.isNotEmpty()
}
