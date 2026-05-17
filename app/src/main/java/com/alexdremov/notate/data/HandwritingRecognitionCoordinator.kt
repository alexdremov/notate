package com.alexdremov.notate.data

import android.graphics.PointF
import android.graphics.RectF
import com.alexdremov.notate.data.region.RegionId
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class HandwritingRecognitionCoordinator(
    private val model: InfiniteCanvasModel,
    private val recognitionManager: HandwritingRecognitionManager,
    private val isEnabledProvider: () -> Boolean,
    private val onOcrUpdated: ((RectF) -> Unit)? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pendingStrokes = ArrayList<Stroke>()
    private val strokeUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val processingRegions = HashSet<RegionId>()

    init {
        // Observe model events for new strokes
        model.events
            .onEach { event ->
                if (event is InfiniteCanvasModel.ModelEvent.ItemsAdded) {
                    val newStrokes =
                        event.items.filterIsInstance<Stroke>().filter {
                            it.style != StrokeType.DASH && it.style != StrokeType.HIGHLIGHTER // Exclude erasers/selection tools and highlighters
                        }
                    if (newStrokes.isNotEmpty()) {
                        synchronized(pendingStrokes) {
                            pendingStrokes.addAll(newStrokes)
                        }
                        strokeUpdateFlow.emit(Unit)
                    }
                }
            }.launchIn(scope)

        // Debounced processing
        strokeUpdateFlow
            .debounce(2000)
            .onEach {
                if (isEnabledProvider()) {
                    processPendingStrokes()
                } else {
                    synchronized(pendingStrokes) {
                        pendingStrokes.clear()
                    }
                }
            }.launchIn(scope)

        // Periodic sweep for unrecognized strokes (e.g. from older documents or erasures)
        scope.launch {
            // Trigger immediate sweep after document open
            if (isEnabledProvider()) {
                sweepUnrecognizedStrokes()
            }
            while (isActive) {
                delay(15000) // Sweep every 15 seconds
                if (isEnabledProvider()) {
                    sweepUnrecognizedStrokes()
                }
            }
        }
    }

    suspend fun sweepUnrecognizedStrokes() {
        val rm = model.getRegionManager() ?: return
        val activeIds = rm.getActiveRegionIds()

        for (rId in activeIds) {
            // Coordination: Skip if this region is currently being processed by real-time logic
            val skip =
                synchronized(processingRegions) {
                    processingRegions.contains(rId)
                }
            if (skip) continue

            val region = rm.getRegionReadOnly(rId) ?: continue

            // 1. Gather all "recognizable" strokes in the region
            val strokesInRegion =
                region.items.filterIsInstance<Stroke>().filter {
                    it.style != StrokeType.DASH && it.style != StrokeType.HIGHLIGHTER
                }
            if (strokesInRegion.isEmpty()) continue

            // 2. Perform algorithmic line detection on ALL strokes in the region
            // This establishes the "ground truth" for how strokes SHOULD be grouped.
            val highLevelClusters = StrokeClusteringManager.clusterStrokes(strokesInRegion)
            val detectedLines = highLevelClusters.flatMap { StrokeClusteringManager.segmentIntoLines(it) }

            // 3. Build a fast lookup for existing OCR blocks by their stroke sets
            // We use a Set<Long> of strokeOrders as the stable identity of an OCR block.
            val existingOcrByStrokes = region.recognizedTexts.associateBy { it.strokeOrders.toSet() }

            val unrecognizedLines = ArrayList<List<Stroke>>()

            for (line in detectedLines) {
                val lineStrokeOrders = line.map { it.strokeOrder }.toSet()

                // INVARIANT CHECK: Does an OCR block exist that matches this exact line?
                if (!existingOcrByStrokes.containsKey(lineStrokeOrders)) {
                    // If not, this line is either new, modified, or fragmented.
                    unrecognizedLines.add(line)
                }
            }

            if (unrecognizedLines.isNotEmpty()) {
                Logger.d("OCRCoordinator", "Sweep found ${unrecognizedLines.size} lines in region $rId violating OCR invariant.")
                for (line in unrecognizedLines) {
                    processStrokesInternal(line)
                    delay(200)
                }
            }
        }
    }

    private suspend fun processPendingStrokes() {
        val strokesToProcess =
            synchronized(pendingStrokes) {
                val copy = ArrayList(pendingStrokes)
                pendingStrokes.clear()
                copy
            }
        processStrokesInternal(strokesToProcess)
    }

    private suspend fun processStrokesInternal(initialStrokes: List<Stroke>) {
        if (initialStrokes.isEmpty()) return

        // 1. Recursive spatial expansion to find all connected strokes and intersecting OCR blocks
        val fullClusterSet = HashSet<Stroke>(initialStrokes)
        val expandedSearchArea = RectF()
        initialStrokes.forEach {
            if (expandedSearchArea.isEmpty) {
                expandedSearchArea.set(
                    it.bounds,
                )
            } else {
                expandedSearchArea.union(it.bounds)
            }
        }
        expandedSearchArea.inset(-150f, -100f)

        // Identify all existing OCR blocks that intersect our current search area
        val intersectingOcrStrokeOrders = HashSet<Long>()
        val totalInvalidateArea = RectF(expandedSearchArea)

        model.getRegionManager()?.getRegionIdsInRect(expandedSearchArea)?.forEach { rId ->
            val region = model.getRegionManager()?.getRegionReadOnly(rId)
            region?.recognizedTexts?.forEach { ocr ->
                val ocrRect = RectF(ocr.x, ocr.y, ocr.x + ocr.width, ocr.y + ocr.height)
                if (RectF.intersects(ocrRect, expandedSearchArea)) {
                    intersectingOcrStrokeOrders.addAll(ocr.strokeOrders)
                    totalInvalidateArea.union(ocrRect)
                }
            }
        }

        // Lock regions affected by the expanded area
        val affectedRegions = model.getRegionManager()?.getRegionIdsInRect(totalInvalidateArea) ?: emptyList()
        synchronized(processingRegions) {
            processingRegions.addAll(affectedRegions)
        }

        try {
            // Find ALL strokes in the final expanded area (including those from intersected OCR blocks)
            model.getRegionManager()?.visitItemsInRect(totalInvalidateArea) { item ->
                if (item is Stroke && item.style != StrokeType.DASH && item.style != StrokeType.HIGHLIGHTER) {
                    // Include if spatially inside OR part of an invalidated OCR block
                    if (RectF.intersects(totalInvalidateArea, item.bounds) ||
                        intersectingOcrStrokeOrders.contains(item.strokeOrder)
                    ) {
                        fullClusterSet.add(item)
                    }
                }
            }

            // 2. High-level Clustering (Group into paragraphs/sections)
            val clusters = StrokeClusteringManager.clusterStrokes(fullClusterSet.toList())

            // 3. Clear existing OCR for the entire affected area ONCE to prevent inter-line conflicts
            model.removeRecognizedTextInRect(totalInvalidateArea)

            for (cluster in clusters) {
                // 4. Line Segmentation (Split into individual horizontal lines)
                val lines = StrokeClusteringManager.segmentIntoLines(cluster)

                // 5. Individual Recognition and Persistence
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val result = recognitionManager.recognizeStrokes(line)
                    if (result != null) {
                        model.addRecognizedText(result)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onOcrUpdated?.invoke(totalInvalidateArea)
            }
        } finally {
            synchronized(processingRegions) {
                processingRegions.removeAll(affectedRegions.toSet())
            }
        }
    }

    fun stop() {
        // RecognitionManager is usually managed externally, but we stop our scope
        scope.launch {
            synchronized(pendingStrokes) {
                pendingStrokes.clear()
            }
        }
    }

    /**
     * Manually triggers recognition for a set of strokes (e.g. after movement).
     */
    fun triggerManualRecognition(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        synchronized(pendingStrokes) {
            pendingStrokes.addAll(strokes)
        }
        scope.launch {
            strokeUpdateFlow.emit(Unit)
        }
    }
}
