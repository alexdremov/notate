package com.example.notate.ui

import android.content.Context
import android.util.TypedValue
import android.view.View

fun Context.dpToPx(dp: Int): Int =
    TypedValue
        .applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics,
        ).toInt()

fun View.dpToPx(dp: Int): Int = context.dpToPx(dp)

fun Context.mmToPx(mm: Float): Float = mm * resources.displayMetrics.xdpi / 25.4f

fun Context.pxToMm(px: Float): Float = px * 25.4f / resources.displayMetrics.xdpi
