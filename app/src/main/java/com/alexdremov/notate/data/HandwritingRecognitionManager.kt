package com.alexdremov.notate.data

import android.content.Context
import android.graphics.RectF
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.util.Logger
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicReference

class HandwritingRecognitionManager(
    private val context: Context,
) {
    private val modelManager = RemoteModelManager.getInstance()
    private var recognizer: DigitalInkRecognizer? = null
    private var currentModel: DigitalInkRecognitionModel? = null

    companion object {
        private const val TAG = "HandwritingRecognition"
        private const val DEFAULT_LANG = "en-US"
    }

    suspend fun ensureModelDownloaded(
        langTag: String = DEFAULT_LANG,
        onProgress: (Boolean) -> Unit = {},
    ): Boolean {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(langTag)
        if (identifier == null) {
            Logger.e(TAG, "Invalid language tag: $langTag")
            return false
        }

        val model = DigitalInkRecognitionModel.builder(identifier).build()
        currentModel = model

        return try {
            val isDownloaded = modelManager.isModelDownloaded(model).await()
            if (!isDownloaded) {
                onProgress(true)
                Logger.i(TAG, "Downloading model for $langTag...")
                val conditions =
                    DownloadConditions
                        .Builder()
                        .build() // Remove requireWifi to allow testing/immediate use if user wants
                modelManager.download(model, conditions).await()
                Logger.i(TAG, "Model $langTag downloaded successfully")
                onProgress(false)
            }
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to download model $langTag", e)
            onProgress(false)
            false
        }
    }

    private fun getRecognizer(): DigitalInkRecognizer? {
        val model = currentModel ?: return null
        if (recognizer == null) {
            recognizer =
                DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build(),
                )
        }
        return recognizer
    }

    suspend fun recognizeStrokes(strokes: List<Stroke>): RecognizedTextData? {
        if (strokes.isEmpty()) return null

        val recognizer =
            getRecognizer() ?: run {
                if (ensureModelDownloaded()) {
                    getRecognizer()
                } else {
                    null
                }
            } ?: return null

        val inkBuilder = Ink.builder()
        val totalBounds = RectF()
        val strokeOrders = ArrayList<Long>()
        var isFirst = true

        for (stroke in strokes) {
            val mlStrokeBuilder = Ink.Stroke.builder()
            for (point in stroke.points) {
                mlStrokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.timestamp))
            }
            inkBuilder.addStroke(mlStrokeBuilder.build())

            if (isFirst) {
                totalBounds.set(stroke.bounds)
                isFirst = false
            } else {
                totalBounds.union(stroke.bounds)
            }
            strokeOrders.add(stroke.strokeOrder)
        }

        return try {
            val result = recognizer.recognize(inkBuilder.build()).await()
            val bestCandidate = result.candidates.firstOrNull() ?: return null

            Logger.d(TAG, "Recognized text: ${bestCandidate.text}")

            RecognizedTextData(
                text = bestCandidate.text,
                x = totalBounds.left,
                y = totalBounds.top,
                width = totalBounds.width(),
                height = totalBounds.height(),
                strokeOrders = strokeOrders,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Recognition failed", e)
            null
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}
