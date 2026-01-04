package com.example.notate.model

sealed class HistoryAction {
    data class Add(
        val strokes: List<Stroke>,
    ) : HistoryAction()

    data class Remove(
        val strokes: List<Stroke>,
    ) : HistoryAction()

    data class Replace(
        val removed: List<Stroke>,
        val added: List<Stroke>,
    ) : HistoryAction()

    data class Batch(
        val actions: List<HistoryAction>,
    ) : HistoryAction()
}
