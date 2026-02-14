package com.alexdremov.notate.ui.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alexdremov.notate.R
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.databinding.DialogPenSettingsBinding
import com.alexdremov.notate.databinding.ItemColorCircleBinding
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.ui.dpToPx
import com.alexdremov.notate.ui.mmToPx
import com.alexdremov.notate.ui.pxToMm
import com.alexdremov.notate.util.ColorUtils
import com.onyx.android.sdk.api.device.EpdDeviceManager
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import java.util.Collections
import kotlin.math.roundToInt

class PenSettingsPopup(
    private val context: Context,
    private val tool: PenTool,
    private val onUpdate: (PenTool) -> Unit,
    private val onRemove: (PenTool) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val binding = DialogPenSettingsBinding.inflate(LayoutInflater.from(context), null, false)
    private val popupWindow =
        PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )

    private var currentTool = tool.copy()

    // Loaded from persistent storage
    private val favorites = PreferencesManager.getFavoriteColors(context).toMutableList()

    private lateinit var colorAdapter: ColorAdapter

    init {
        popupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, android.R.color.transparent))
        popupWindow.elevation = 16f

        // Trigger onDismiss only when the view is truly detached to ensure it's gone from screen
        binding.root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    onDismiss()
                }
            },
        )

        setupMainUI()
        setupPresetsPage()
        setupCustomPickerPage()
    }

    private fun refreshHighQuality(view: View) {
        // Exit Fast Mode if active
        com.alexdremov.notate.util.EpdFastModeController
            .exitFastMode()
        view.post {
            EpdController.invalidate(view, UpdateMode.GC)
        }
    }

    private fun setupMainUI() {
        // Common: Remove Tool
        binding.btnRemove.setOnClickListener {
            onRemove(currentTool)
            dismiss()
        }

        if (currentTool.type == com.alexdremov.notate.model.ToolType.TEXT) {
            setupTextUI()
            return
        }

        // Layout is wrapped in ScrollView in XML to prevent clipping on smaller screens
        if (currentTool.type == com.alexdremov.notate.model.ToolType.ERASER) {
            setupEraserUI()
        } else if (currentTool.type == com.alexdremov.notate.model.ToolType.SELECT) {
            setupSelectUI()
        } else {
            setupPenUI()
        }

        // --- Width Selection (mm <-> px) ---
        // Set initial slider range and value
        val initialMm = context.pxToMm(currentTool.width)
        val roundedMm = (initialMm * 10).roundToInt() / 10f
        val maxMm = if (currentTool.type == com.alexdremov.notate.model.ToolType.ERASER) 50f else currentTool.strokeType.maxWidthMm

        binding.sliderWidth.valueTo = maxMm
        binding.sliderWidth.value = roundedMm.coerceIn(CanvasConfig.TOOLS_MIN_STROKE_MM, maxMm)
        binding.tvWidthValue.text = String.format("%.1f mm", binding.sliderWidth.value)

        binding.sliderWidth.addOnChangeListener { _, value, _ ->
            val px = context.mmToPx(value)
            binding.tvWidthValue.text = String.format("%.1f mm", value)
            updateTool { it.copy(width = px) }
        }

        binding.sliderWidth.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    com.alexdremov.notate.util.EpdFastModeController
                        .enterFastMode()
                }

                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    com.alexdremov.notate.util.EpdFastModeController
                        .exitFastMode()
                }
            },
        )
    }

    private fun setupTextUI() {
        binding.tvTitle.text = "Text Settings"
        binding.gridStyles.visibility = View.GONE
        binding.rgEraserTypes.visibility = View.GONE
        binding.divider1.visibility = View.GONE

        // Show Font Size (Width)
        binding.layoutWidthLabels.visibility = View.VISIBLE
        binding.tvWidthLabel.text = "Font Size"
        binding.sliderWidth.visibility = View.VISIBLE

        // Divider before colors
        binding.divider2.visibility = View.VISIBLE

        // Show Color
        binding.tvColorLabel.visibility = View.VISIBLE
        binding.tvColorName.visibility = View.VISIBLE
        binding.recyclerColors.visibility = View.VISIBLE
        binding.btnRemove.visibility = View.VISIBLE

        // Configure Slider for Font Size (px)
        // Range: 10px to 100px
        val currentSize = currentTool.width
        binding.sliderWidth.valueFrom = 10f
        binding.sliderWidth.valueTo = 100f
        binding.sliderWidth.stepSize = 1f
        binding.sliderWidth.value = currentSize.coerceIn(10f, 100f)
        binding.tvWidthValue.text = "${binding.sliderWidth.value.toInt()} px"

        binding.sliderWidth.addOnChangeListener { _, value, _ ->
            binding.tvWidthValue.text = "${value.toInt()} px"
            updateTool { it.copy(width = value) }
        }

        // Initialize Color UI (reusing Pen logic parts)
        binding.tvColorName.text =
            com.alexdremov.notate.util.ColorNamer
                .getColorName(currentTool.color)

        // Re-use color adapter logic from setupPenUI or init
        // We need to ensure colorAdapter is initialized.
        // It is initialized in setupPenUI currently. I should move it to init or a common setup.
        setupColorAdapter()
    }

    private fun setupColorAdapter() {
        colorAdapter =
            ColorAdapter(
                colors = favorites,
                onColorSelected = { color ->
                    updateTool { it.copy(color = color) }
                    binding.tvColorName.text =
                        com.alexdremov.notate.util.ColorNamer
                            .getColorName(color)
                },
                onAddClicked = {
                    binding.viewFlipper.displayedChild = 1 // Go to Presets Page (Index 1)
                    refreshHighQuality(binding.gridColorPresets)
                },
            )
        binding.recyclerColors.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(context, 2, androidx.recyclerview.widget.GridLayoutManager.HORIZONTAL, false)
        binding.recyclerColors.adapter = colorAdapter

        binding.recyclerColors.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        com.alexdremov.notate.util.EpdFastModeController
                            .enterFastMode()
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        com.alexdremov.notate.util.EpdFastModeController
                            .exitFastMode()
                    }
                }
            },
        )

        // Attach ItemTouchHelper (reusing code from setupPenUI)
        val itemTouchHelper =
            ItemTouchHelper(
                object : ItemTouchHelper.Callback() {
                    private var isOutside = false

                    override fun getMovementFlags(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        if (viewHolder.adapterPosition == favorites.size) {
                            return makeMovementFlags(0, 0)
                        }
                        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        val swipeFlags = 0
                        return makeMovementFlags(dragFlags, swipeFlags)
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder,
                    ): Boolean {
                        if (target.adapterPosition == favorites.size) return false

                        val fromPos = viewHolder.adapterPosition
                        val toPos = target.adapterPosition
                        if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                        Collections.swap(favorites, fromPos, toPos)
                        colorAdapter.notifyItemMoved(fromPos, toPos)
                        PreferencesManager.saveFavoriteColors(context, favorites)
                        return true
                    }

                    override fun onSwiped(
                        viewHolder: RecyclerView.ViewHolder,
                        direction: Int,
                    ) {
                    }

                    override fun onChildDraw(
                        c: android.graphics.Canvas,
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        dX: Float,
                        dY: Float,
                        actionState: Int,
                        isCurrentlyActive: Boolean,
                    ) {
                        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                            val itemView = viewHolder.itemView
                            val centerX = itemView.left + dX + itemView.width / 2f
                            val centerY = itemView.top + dY + itemView.height / 2f

                            isOutside = centerX < 0 || centerX > recyclerView.width ||
                                centerY < 0 || centerY > recyclerView.height

                            itemView.alpha = if (isOutside) 0.5f else 1.0f
                        }
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        viewHolder.itemView.alpha = 1.0f

                        if (isOutside) {
                            val pos = viewHolder.adapterPosition
                            if (pos != RecyclerView.NO_POSITION && pos < favorites.size) {
                                favorites.removeAt(pos)
                                colorAdapter.notifyItemRemoved(pos)
                                PreferencesManager.saveFavoriteColors(context, favorites)
                            }
                            isOutside = false
                        }
                    }

                    override fun isLongPressDragEnabled(): Boolean = true
                },
            )
        itemTouchHelper.attachToRecyclerView(binding.recyclerColors)
    }

    private fun setupSelectUI() {
        binding.tvTitle.text = "Select Tool"
        binding.gridStyles.visibility = View.GONE
        binding.rgEraserTypes.visibility = View.VISIBLE
        binding.divider2.visibility = View.GONE
        binding.tvColorLabel.visibility = View.GONE
        binding.tvColorName.visibility = View.GONE
        binding.recyclerColors.visibility = View.GONE
        binding.btnRemove.visibility = View.GONE
        binding.layoutWidthLabels.visibility = View.GONE
        binding.sliderWidth.visibility = View.GONE
        binding.divider1.visibility = View.GONE

        // Reuse Eraser Radio Buttons but change text
        binding.rbEraserStandard.text = "Rectangle"
        binding.rbEraserStroke.text = "Lasso"
        binding.rbEraserLasso.visibility = View.GONE // Only need 2 options

        // Bind Selection Type
        when (currentTool.selectionType) {
            com.alexdremov.notate.model.SelectionType.RECTANGLE -> binding.rbEraserStandard.isChecked = true
            com.alexdremov.notate.model.SelectionType.LASSO -> binding.rbEraserStroke.isChecked = true
        }

        binding.rgEraserTypes.setOnCheckedChangeListener { _, checkedId ->
            val type =
                when (checkedId) {
                    R.id.rbEraserStandard -> com.alexdremov.notate.model.SelectionType.RECTANGLE
                    R.id.rbEraserStroke -> com.alexdremov.notate.model.SelectionType.LASSO
                    else -> com.alexdremov.notate.model.SelectionType.RECTANGLE
                }
            updateTool { it.copy(selectionType = type) }
        }
    }

    private fun setupEraserUI() {
        binding.tvTitle.text = "Eraser Settings"
        binding.gridStyles.visibility = View.GONE
        binding.rgEraserTypes.visibility = View.VISIBLE
        binding.divider2.visibility = View.GONE
        binding.tvColorLabel.visibility = View.GONE
        binding.tvColorName.visibility = View.GONE
        binding.recyclerColors.visibility = View.GONE
        binding.btnRemove.visibility = View.GONE

        // Bind Eraser Type Selection
        when (currentTool.eraserType) {
            com.alexdremov.notate.model.EraserType.STANDARD -> binding.rbEraserStandard.isChecked = true
            com.alexdremov.notate.model.EraserType.STROKE -> binding.rbEraserStroke.isChecked = true
            com.alexdremov.notate.model.EraserType.LASSO -> binding.rbEraserLasso.isChecked = true
        }

        updateWidthSliderVisibility(currentTool.eraserType)

        binding.rgEraserTypes.setOnCheckedChangeListener { _, checkedId ->
            val type =
                when (checkedId) {
                    R.id.rbEraserStandard -> com.alexdremov.notate.model.EraserType.STANDARD
                    R.id.rbEraserStroke -> com.alexdremov.notate.model.EraserType.STROKE
                    R.id.rbEraserLasso -> com.alexdremov.notate.model.EraserType.LASSO
                    else -> com.alexdremov.notate.model.EraserType.STANDARD
                }
            updateWidthSliderVisibility(type)
            updateTool { it.copy(eraserType = type) }
        }
    }

    private fun updateWidthSliderVisibility(eraserType: com.alexdremov.notate.model.EraserType) {
        val isLasso = eraserType == com.alexdremov.notate.model.EraserType.LASSO
        val visibility = if (isLasso) View.GONE else View.VISIBLE
        binding.layoutWidthLabels.visibility = visibility
        binding.sliderWidth.visibility = visibility
        binding.divider1.visibility = visibility
    }

    private fun setupPenUI() {
        binding.tvTitle.text = "Pen Style"
        binding.gridStyles.visibility = View.VISIBLE
        binding.rgEraserTypes.visibility = View.GONE
        binding.divider1.visibility = View.VISIBLE
        binding.layoutWidthLabels.visibility = View.VISIBLE
        binding.sliderWidth.visibility = View.VISIBLE
        binding.divider2.visibility = View.VISIBLE
        binding.tvColorLabel.visibility = View.VISIBLE
        binding.tvColorName.visibility = View.VISIBLE
        binding.recyclerColors.visibility = View.VISIBLE
        binding.btnRemove.visibility = View.VISIBLE

        // --- Style Selection ---
        val styleViews =
            mapOf(
                StrokeType.FOUNTAIN to binding.styleFountain,
                StrokeType.BALLPOINT to binding.styleBallpoint,
                StrokeType.FINELINER to binding.styleFineliner,
                StrokeType.HIGHLIGHTER to binding.styleHighlighter,
                StrokeType.BRUSH to binding.styleBrush,
                StrokeType.CHARCOAL to binding.styleCharcoal,
                StrokeType.DASH to binding.styleDash,
            )

        fun updateSliderRange(type: StrokeType) {
            val maxMm = type.maxWidthMm

            // If the current value is outside the new range, clamp it first
            if (binding.sliderWidth.value > maxMm) {
                binding.sliderWidth.value = maxMm
            }
            // Update the max value
            binding.sliderWidth.valueTo = maxMm
        }

        fun updateStyleSelection(selectedType: StrokeType) {
            styleViews.forEach { (type, view) ->
                view.isSelected = (type == selectedType)
                if (type == selectedType) {
                    view.setColorFilter(Color.BLACK) // Darken icon if needed, or rely on selector background
                } else {
                    view.clearColorFilter()
                }
            }
            updateSliderRange(selectedType)
        }

        styleViews.forEach { (type, view) ->
            view.setOnClickListener {
                updateTool { it.copy(strokeType = type) }
                updateStyleSelection(type)
            }
        }

        // Initialize selection UI state
        updateStyleSelection(currentTool.strokeType)

        // --- Color Name ---
        binding.tvColorName.text =
            com.alexdremov.notate.util.ColorNamer
                .getColorName(currentTool.color)

        // --- Favorites RecyclerView ---
        colorAdapter =
            ColorAdapter(
                colors = favorites,
                onColorSelected = { color ->
                    updateTool { it.copy(color = color) }
                    binding.tvColorName.text =
                        com.alexdremov.notate.util.ColorNamer
                            .getColorName(color)
                },
                onAddClicked = {
                    binding.viewFlipper.displayedChild = 1 // Go to Presets Page (Index 1)
                    refreshHighQuality(binding.gridColorPresets)
                },
            )
        binding.recyclerColors.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(context, 2, androidx.recyclerview.widget.GridLayoutManager.HORIZONTAL, false)
        binding.recyclerColors.adapter = colorAdapter

        binding.recyclerColors.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        com.alexdremov.notate.util.EpdFastModeController
                            .enterFastMode()
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        com.alexdremov.notate.util.EpdFastModeController
                            .exitFastMode()
                    }
                }
            },
        )

        // Custom Drag & Drop + Drop-to-Delete
        val itemTouchHelper =
            ItemTouchHelper(
                object : ItemTouchHelper.Callback() {
                    private var isOutside = false

                    override fun getMovementFlags(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        // Prevent dragging the "Add" button (last item)
                        if (viewHolder.adapterPosition == favorites.size) {
                            return makeMovementFlags(0, 0)
                        }
                        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        val swipeFlags = 0 // We handle deletion via drop-outside
                        return makeMovementFlags(dragFlags, swipeFlags)
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder,
                    ): Boolean {
                        // Prevent dropping on the "Add" button
                        if (target.adapterPosition == favorites.size) return false

                        val fromPos = viewHolder.adapterPosition
                        val toPos = target.adapterPosition
                        if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                        Collections.swap(favorites, fromPos, toPos)
                        colorAdapter.notifyItemMoved(fromPos, toPos)
                        PreferencesManager.saveFavoriteColors(context, favorites)
                        return true
                    }

                    override fun onSwiped(
                        viewHolder: RecyclerView.ViewHolder,
                        direction: Int,
                    ) {
                        // Not used since swipeFlags is 0
                    }

                    override fun onChildDraw(
                        c: android.graphics.Canvas,
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        dX: Float,
                        dY: Float,
                        actionState: Int,
                        isCurrentlyActive: Boolean,
                    ) {
                        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                            val itemView = viewHolder.itemView
                            // Use center of item to determine if it's "outside" the recycler view bounds
                            val centerX = itemView.left + dX + itemView.width / 2f
                            val centerY = itemView.top + dY + itemView.height / 2f

                            isOutside = centerX < 0 || centerX > recyclerView.width ||
                                centerY < 0 || centerY > recyclerView.height

                            // Visual feedback: dim item if it's about to be deleted
                            itemView.alpha = if (isOutside) 0.5f else 1.0f
                        }
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        viewHolder.itemView.alpha = 1.0f

                        if (isOutside) {
                            val pos = viewHolder.adapterPosition
                            if (pos != RecyclerView.NO_POSITION && pos < favorites.size) {
                                favorites.removeAt(pos)
                                colorAdapter.notifyItemRemoved(pos)
                                PreferencesManager.saveFavoriteColors(context, favorites)
                            }
                            isOutside = false
                        }
                    }

                    override fun isLongPressDragEnabled(): Boolean = true
                },
            )
        itemTouchHelper.attachToRecyclerView(binding.recyclerColors)
    }

    private fun setupPresetsPage() {
        // Back to Main
        binding.btnBackFromPresets.setOnClickListener {
            binding.viewFlipper.displayedChild = 0
            refreshHighQuality(binding.recyclerColors)
        }

        // Go to Custom Picker
        binding.btnGoToCustomColor.setOnClickListener {
            binding.viewFlipper.displayedChild = 2
            refreshHighQuality(binding.colorPickerView)
        }

        // --- Presets ---
        val presets =
            listOf(
                // Basic / Professional
                Color.BLACK,
                Color.WHITE,
                Color.parseColor("#2B3E4F"), // Dark Blue
                Color.parseColor("#2331c9"), // Navy Blue
                Color.parseColor("#8C0000"), // Dark Red
                Color.parseColor("#638666"), // Forest Green
                Color.parseColor("#1A3817"), // Dark Green
                Color.parseColor("#7b19f2"), // Deep Purple
                Color.parseColor("#B48EAD"), // Muted Purple
                // Vibrant / Standard
                Color.parseColor("#0081eb"), // Blue
                Color.parseColor("#F23005"), // Orange
                Color.parseColor("#009688"), // Teal
                Color.parseColor("#F2727D"), // Coral
                Color.parseColor("#F2C94C"), // Gold
                Color.parseColor("#9B51E0"), // Lavender
                Color.parseColor("#EBCB8B"), // Sand
                Color.parseColor("#65ABC2"), // Sky Blue
                // Pastel / Soft
                Color.parseColor("#BBDEFB"), // Pastel Blue
                Color.parseColor("#FFCDD2"), // Pastel Pink
                Color.parseColor("#C8E6C9"), // Pastel Green
                Color.parseColor("#FFF9C4"), // Pastel Yellow
                Color.parseColor("#FFE0B2"), // Pastel Orange
                Color.parseColor("#E1BEE7"), // Pastel Purple
            )

        binding.gridColorPresets.removeAllViews()
        presets.forEach { color ->
            val frame = FrameLayout(context)
            val params =
                GridLayout.LayoutParams().apply {
                    width = context.dpToPx(48)
                    height = context.dpToPx(48)
                    setMargins(context.dpToPx(4), context.dpToPx(4), context.dpToPx(4), context.dpToPx(4))
                }
            frame.layoutParams = params

            val circle =
                View(context).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(ColorUtils.adjustColorForMenuDisplay(color))
                            setStroke(context.dpToPx(1), Color.LTGRAY)
                        }
                }
            frame.addView(
                circle,
                FrameLayout.LayoutParams(context.dpToPx(32), context.dpToPx(32)).apply {
                    gravity = android.view.Gravity.CENTER
                },
            )

            frame.setOnClickListener {
                addNewColor(color)
            }
            binding.gridColorPresets.addView(frame)
        }
    }

    private fun setupCustomPickerPage() {
        // Back to Presets
        binding.btnBackFromCustom.setOnClickListener {
            binding.viewFlipper.displayedChild = 1
            refreshHighQuality(binding.gridColorPresets)
        }

        // Attach Sliders
        binding.colorPickerView.attachAlphaSlider(binding.alphaSlideBar)
        binding.colorPickerView.attachBrightnessSlider(binding.brightnessSlideBar)

        // Set Initial Color
        binding.colorPickerView.setInitialColor(currentTool.color)
        binding.tvColorDescription.text =
            com.alexdremov.notate.util.ColorNamer
                .getColorName(currentTool.color)

        // Realtime Preview Listener
        binding.colorPickerView.setColorListener(
            com.skydoves.colorpickerview.listeners.ColorEnvelopeListener { envelope, fromUser ->
                val color = envelope.color
                val shape =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.adjustColorForMenuDisplay(color))
                        setStroke(context.dpToPx(1), Color.LTGRAY)
                    }
                binding.viewCurrentColorPreview.background = shape
                binding.tvColorDescription.text =
                    com.alexdremov.notate.util.ColorNamer
                        .getColorName(color)
            },
        )

        binding.btnAddCustomColor.setOnClickListener {
            val color = binding.colorPickerView.color
            addNewColor(color)
        }
    }

    private fun addNewColor(color: Int) {
        if (!favorites.contains(color)) {
            favorites.add(favorites.size, color)
            colorAdapter.notifyItemInserted(favorites.size - 1)
            PreferencesManager.saveFavoriteColors(context, favorites) // Save new color
        }
        updateTool { it.copy(color = color) }
        binding.tvColorName.text =
            com.alexdremov.notate.util.ColorNamer
                .getColorName(color)

        // Return to Main Page
        binding.viewFlipper.displayedChild = 0
        refreshHighQuality(binding.recyclerColors)

        // Scroll to the new color
        binding.recyclerColors.scrollToPosition(favorites.size - 1)
    }

    private fun updateTool(modifier: (PenTool) -> PenTool) {
        currentTool = modifier(currentTool)
        onUpdate(currentTool)
    }

    fun show(
        parent: View,
        targetRect: Rect,
    ) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val anchorX = targetRect.left
        val anchorY = targetRect.top
        val anchorHeight = targetRect.height()
        val anchorWidth = targetRect.width()

        // Measure root view to get dimensions before showing
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST),
        )
        val popupWidth = binding.root.measuredWidth
        val popupHeight = binding.root.measuredHeight

        // Calculate horizontal offset to keep popup on screen
        var xPos = anchorX
        val padding = context.dpToPx(16)

        // If anchor + popup would go off-screen right
        if (xPos + popupWidth > screenWidth - padding) {
            // Align right edge of popup with right edge of anchor, or push left
            xPos = (screenWidth - padding) - popupWidth
        }

        // If anchor + xOffset would go off-screen left
        if (xPos < padding) {
            xPos = padding
        }

        // Decide vertical position based on screen position (Top half vs Bottom half)
        val yPos: Int
        if (anchorY > screenHeight / 2) {
            // Show ABOVE
            yPos = anchorY - popupHeight - context.dpToPx(10)
        } else {
            // Show BELOW
            yPos = anchorY + anchorHeight + context.dpToPx(10)
        }

        popupWindow.showAtLocation(parent, android.view.Gravity.NO_GRAVITY, xPos, yPos)
        refreshHighQuality(binding.root)
    }

    /**
     * Shows the popup relative to the anchor view.
     * Positions it above the anchor if it's in the bottom half of the screen,
     * otherwise shows it below.
     * Includes logic to keep the popup within horizontal screen bounds.
     */
    fun show(anchor: View) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]

        // Measure root view to get dimensions before showing
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST),
        )
        val popupWidth = binding.root.measuredWidth
        val popupHeight = binding.root.measuredHeight

        // Calculate horizontal offset to keep popup on screen
        var xOffset = 0
        val padding = context.dpToPx(16)

        // If anchor + popup would go off-screen right
        if (anchorX + popupWidth > screenWidth - padding) {
            xOffset = (screenWidth - padding) - (anchorX + popupWidth)
        }

        // If anchor + xOffset would go off-screen left
        if (anchorX + xOffset < padding) {
            xOffset = padding - anchorX
        }

        // Decide vertical position based on screen position (Top half vs Bottom half)
        if (anchorY > screenHeight / 2) {
            // Show ABOVE
            val yOffset = -(popupHeight + anchor.height + context.dpToPx(10))
            popupWindow.showAsDropDown(anchor, xOffset, yOffset)
        } else {
            // Show BELOW
            popupWindow.showAsDropDown(anchor, xOffset, context.dpToPx(10))
        }

        refreshHighQuality(binding.root)
    }

    fun dismiss() {
        popupWindow.dismiss()
    }

    // --- Inner Adapter ---
    inner class ColorAdapter(
        private val colors: List<Int>,
        private val onColorSelected: (Int) -> Unit,
        private val onAddClicked: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val VIEW_TYPE_COLOR = 0
        private val VIEW_TYPE_ADD = 1

        override fun getItemCount(): Int = colors.size + 1 // +1 for Add button

        override fun getItemViewType(position: Int): Int = if (position < colors.size) VIEW_TYPE_COLOR else VIEW_TYPE_ADD

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val binding = ItemColorCircleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return if (viewType == VIEW_TYPE_COLOR) ColorViewHolder(binding) else AddViewHolder(binding)
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            if (holder is ColorViewHolder) {
                holder.bind(colors[position])
            } else if (holder is AddViewHolder) {
                holder.bind()
            }
        }

        inner class ColorViewHolder(
            private val itemBinding: ItemColorCircleBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(color: Int) {
                itemBinding.ivAddIcon.visibility = View.GONE

                val shape =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ColorUtils.adjustColorForMenuDisplay(color))
                        setStroke(context.dpToPx(1), Color.LTGRAY) // Border for visibility
                    }
                itemBinding.viewColorCircle.background = shape

                itemView.setOnClickListener { onColorSelected(color) }
            }
        }

        inner class AddViewHolder(
            private val itemBinding: ItemColorCircleBinding,
        ) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind() {
                itemBinding.ivAddIcon.visibility = View.VISIBLE
                itemBinding.viewColorCircle.setBackgroundResource(R.drawable.bg_tool_selected) // Use selector or simple circle
                // We want a simple circle outline or gray bg
                val shape =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setStroke(context.dpToPx(2), Color.LTGRAY, context.dpToPx(4).toFloat(), context.dpToPx(4).toFloat())
                    }
                itemBinding.viewColorCircle.background = shape

                itemView.setOnClickListener { onAddClicked() }
            }
        }
    }
}
