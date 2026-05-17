package com.alexdremov.notate.data

import android.graphics.PointF
import android.graphics.RectF
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

@OptIn(FlowPreview::class)
class HandwritingRecognitionCoordinator(
    private val model: InfiniteCanvasModel,
    private val recognitionManager: HandwritingRecognitionManager,
    private val isEnabledProvider: () -> Boolean,
    private val onOcrUpdated: ((RectF) -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pendingStrokes = ArrayList<Stroke>()
    private val strokeUpdateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // Observe model events for new strokes
        model.events
            .onEach { event ->
                if (event is InfiniteCanvasModel.ModelEvent.ItemsAdded) {
                    val newStrokes = event.items.filterIsInstance<Stroke>().filter {
                        it.style != StrokeType.DASH // Exclude erasers/selection tools
                    }
                    if (newStrokes.isNotEmpty()) {
                        synchronized(pendingStrokes) {
                            pendingStrokes.addAll(newStrokes)
                        }
                        strokeUpdateFlow.emit(Unit)
                    }
                }
            }
            .launchIn(scope)

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
            }
            .launchIn(scope)

        // Periodic sweep for unrecognized strokes (e.g. from older documents or erasures)
        scope.launch {
            while (isActive) {
                delay(10000) // Sweep every 10 seconds
                if (isEnabledProvider()) {
                    sweepUnrecognizedStrokes()
                }
            }
        }
    }

    private fun clusterStrokesSpatially(strokes: List<Stroke>, maxDistance: Float): List<List<Stroke>> {
        val clusters = ArrayList<MutableList<Stroke>>()
        for (stroke in strokes) {
            val strokeBounds = RectF(stroke.bounds).apply { inset(-maxDistance, -maxDistance) }
            
            // Find all clusters that intersect
            val intersectingClusters = clusters.filter { cluster ->
                cluster.any { s -> 
                    val cb = RectF(s.bounds).apply { inset(-maxDistance, -maxDistance) }
                    RectF.intersects(strokeBounds, cb)
                }
            }
            
            if (intersectingClusters.isEmpty()) {
                clusters.add(mutableListOf(stroke))
            } else {
                val firstCluster = intersectingClusters.first()
                firstCluster.add(stroke)
                for (i in 1 until intersectingClusters.size) {
                    firstCluster.addAll(intersectingClusters[i])
                    clusters.remove(intersectingClusters[i])
                }
            }
        }
        return clusters
    }

    private suspend fun sweepUnrecognizedStrokes() {
        val rm = model.getRegionManager() ?: return
        val activeIds = rm.getActiveRegionIds()
        
        for (rId in activeIds) {
            val region = rm.getRegionReadOnly(rId) ?: continue
            val unrecognizedInRegion = ArrayList<Stroke>()
            
            for (item in region.items) {
                if (item is Stroke && item.style != StrokeType.DASH) {
                    val strokeCenter = PointF(item.bounds.centerX(), item.bounds.centerY())
                    var isRecognized = false
                    for (ocr in region.recognizedTexts) {
                        val ocrBounds = RectF(ocr.x, ocr.y, ocr.x + ocr.width, ocr.y + ocr.height)
                        // Heuristic: if stroke center is within OCR bounds, it's recognized
                        if (ocrBounds.contains(strokeCenter.x, strokeCenter.y) || RectF.intersects(ocrBounds, item.bounds)) {
                            isRecognized = true
                            break
                        }
                    }
                    if (!isRecognized) {
                        unrecognizedInRegion.add(item)
                    }
                }
            }
            
            if (unrecognizedInRegion.isNotEmpty()) {
                Logger.d("OCRCoordinator", "Sweep found ${unrecognizedInRegion.size} unrecognized strokes in region $rId")
                // Cluster the strokes spatially to avoid sending the whole region at once
                val clusters = clusterStrokesSpatially(unrecognizedInRegion, maxDistance = 150f)
                for (cluster in clusters) {
                    processStrokeCluster(cluster)
                    // Yield to avoid freezing the background thread
                    delay(200)
                }
            }
        }
    }

    private suspend fun processPendingStrokes() {
        val strokesToProcess = synchronized(pendingStrokes) {
            val copy = ArrayList(pendingStrokes)
            pendingStrokes.clear()
            copy
        }
        
        if (strokesToProcess.isEmpty()) return
        
        val clusters = clusterStrokesSpatially(strokesToProcess, maxDistance = 150f)
        for (cluster in clusters) {
            processStrokeCluster(cluster)
            delay(100)
        }
    }

    private suspend fun processStrokeCluster(strokesToProcess: List<Stroke>) {
        if (strokesToProcess.isEmpty()) return

        Logger.d("OCRCoordinator", "Processing cluster of ${strokesToProcess.size} strokes")

        // Group strokes by spatial proximity and temporal overlap.
        val totalBounds = RectF()
        var isFirst = true
        for (s in strokesToProcess) {
            if (isFirst) {
                totalBounds.set(s.bounds)
                isFirst = false
            } else {
                totalBounds.union(s.bounds)
            }
        }

        // Expand search area to catch adjacent letters/words and existing OCR blocks
        // Generous vertical padding (-100f) to allow newlines/paragraph grouping.
        val searchArea = RectF(totalBounds).apply { inset(-150f, -100f) }
        
        val intersectingOcrStrokeOrders = HashSet<Long>()
        val finalArea = RectF(totalBounds)
        
        // Find existing OCR blocks that intersect the search area
        model.getRegionManager()?.getRegionIdsInRect(searchArea)?.forEach { rId ->
            val region = model.getRegionManager()?.getRegionReadOnly(rId)
            region?.recognizedTexts?.forEach { ocr ->
                val ocrRect = RectF(ocr.x, ocr.y, ocr.x + ocr.width, ocr.y + ocr.height)
                if (RectF.intersects(ocrRect, searchArea)) {
                    intersectingOcrStrokeOrders.addAll(ocr.strokeOrders)
                    finalArea.union(ocrRect)
                }
            }
        }
        
        val finalStrokesSet = HashSet<Stroke>()
        finalStrokesSet.addAll(strokesToProcess)
        
        // If we touched existing OCR blocks, grab all their strokes so we don't truncate words
        if (intersectingOcrStrokeOrders.isNotEmpty()) {
            model.getRegionManager()?.visitItemsInRect(finalArea) { item ->
                if (item is Stroke && item.style != StrokeType.DASH && item.style != StrokeType.HIGHLIGHTER) {
                    if (intersectingOcrStrokeOrders.contains(item.strokeOrder)) {
                        finalStrokesSet.add(item)
                    }
                }
            }
        }

        // Spatially sort strokes to ensure correct word order (top-to-bottom, left-to-right)
        val sortedByY = finalStrokesSet.sortedBy { it.bounds.centerY() }
        val lines = ArrayList<MutableList<Stroke>>()
        for (stroke in sortedByY) {
            val lastLine = lines.lastOrNull()
            if (lastLine != null) {
                val avgY = lastLine.map { it.bounds.centerY() }.average().toFloat()
                // If center Y is within 60px of the line's average center, consider it the same line
                if (kotlin.math.abs(stroke.bounds.centerY() - avgY) < 60f) {
                    lastLine.add(stroke)
                    continue
                }
            }
            lines.add(mutableListOf(stroke))
        }
        val finalStrokes = lines.flatMap { line -> line.sortedBy { it.bounds.left } }

        // Invalidate old OCR in the area of ALL strokes we are recognizing
        val invalidateArea = RectF()
        isFirst = true
        for (s in finalStrokes) {
            if (isFirst) {
                invalidateArea.set(s.bounds)
                isFirst = false
            } else {
                invalidateArea.union(s.bounds)
            }
        }
        
        // Safety check, ensure invalidateArea isn't wildly wrong
        if (!invalidateArea.isEmpty || finalStrokes.isNotEmpty()) {
            model.removeRecognizedTextInRect(invalidateArea)
        }

        val result = recognitionManager.recognizeStrokes(finalStrokes)
        if (result != null) {
            model.addRecognizedText(result)
            onOcrUpdated?.invoke(invalidateArea)
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
     }
    }
}
