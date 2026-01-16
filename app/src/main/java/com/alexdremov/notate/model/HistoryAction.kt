package com.alexdremov.notate.model

sealed class HistoryAction {
    data class Add(
        val items: List<CanvasItem>,
    ) : HistoryAction()

    data class Remove(
        val items: List<CanvasItem>,
    ) : HistoryAction()

    data class Replace(
        val removed: List<CanvasItem>,
        val added: List<CanvasItem>,
    ) : HistoryAction()

    data class Batch(
        val actions: List<HistoryAction>,
    ) : HistoryAction()
}
