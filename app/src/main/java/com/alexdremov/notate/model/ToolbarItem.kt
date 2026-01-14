@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
sealed class ToolbarItem {
    abstract val id: String

    @Serializable
    data class Pen(
        @ProtoNumber(1)
        val penTool: PenTool,
    ) : ToolbarItem() {
        override val id: String get() = penTool.id
    }

    @Serializable
    data class Eraser(
        @ProtoNumber(1)
        val penTool: PenTool,
    ) : ToolbarItem() {
        override val id: String get() = penTool.id
    }

    @Serializable
    data class Select(
        @ProtoNumber(1)
        val penTool: PenTool,
    ) : ToolbarItem() {
        override val id: String get() = penTool.id
    }

    @Serializable
    data class Action(
        @ProtoNumber(1)
        val actionType: ActionType,
    ) : ToolbarItem() {
        override val id: String get() = actionType.name
    }

    @Serializable
    data class Widget(
        @ProtoNumber(1)
        val widgetType: WidgetType,
    ) : ToolbarItem() {
        override val id: String get() = widgetType.name
    }
}

@Serializable
enum class ActionType {
    UNDO,
    REDO,
}

@Serializable
enum class WidgetType {
    PAGE_NAVIGATION,
}
