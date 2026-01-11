package com.alexdremov.notate.util

import com.alexdremov.notate.model.Stroke
import java.util.ArrayList

object ClipboardManager {
    private val copiedStrokes = ArrayList<Stroke>()

    fun copy(strokes: Collection<Stroke>) {
        copiedStrokes.clear()
        copiedStrokes.addAll(strokes)
    }

    fun getStrokes(): List<Stroke> = ArrayList(copiedStrokes)

    fun hasContent() = copiedStrokes.isNotEmpty()
}
