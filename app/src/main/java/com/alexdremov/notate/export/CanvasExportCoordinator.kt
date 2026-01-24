package com.alexdremov.notate.export

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import com.alexdremov.notate.R
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coordinates the UI and IO aspects of exporting a canvas.
 * Handles:
 * - Launching File Picker (via launcher callback)
 * - Showing/Hiding Progress Dialog
 * - Calling PdfExporter with correct streams
 * - Launching Share Intents
 */
class CanvasExportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val modelProvider: () -> InfiniteCanvasModel,
    private val exportLauncher: ActivityResultLauncher<String>,
) {
    private var progressDialog: AlertDialog? = null
    private var tvProgressMessage: TextView? = null
    private var progressBar: ProgressBar? = null

    // State to hold pending export options while waiting for file picker result
    private var pendingExportIsVector: Boolean = true

    fun requestExport(isVector: Boolean) {
        pendingExportIsVector = isVector
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        exportLauncher.launch("Note_$timestamp.pdf")
    }

    fun onFilePickerResult(uri: Uri?) {
        if (uri != null) {
            performExportToUri(uri, pendingExportIsVector)
        }
    }

    fun requestShare(isVector: Boolean) {
        performShare(isVector)
    }

    private fun performExportToUri(
        uri: Uri,
        isVector: Boolean,
    ) {
        showProgress()

        scope.launch {
            try {
                val scale =
                    com.alexdremov.notate.data.PreferencesManager
                        .getPdfExportScale(context)
                // Open Stream
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    PdfExporter.export(
                        context,
                        modelProvider(),
                        outputStream,
                        isVector,
                        object : PdfExporter.ProgressCallback {
                            override fun onProgress(
                                progress: Int,
                                message: String,
                            ) {
                                scope.launch(Dispatchers.Main) {
                                    updateProgress(progress, message)
                                }
                            }
                        },
                        bitmapScale = scale,
                    )
                } ?: throw Exception("Could not open output stream for $uri")

                withContext(Dispatchers.Main) {
                    hideProgress()
                    Logger.showToUser("Export Successful")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    Logger.e("Export", "Export Failed: ${e.localizedMessage}", e, showToUser = true)
                }
            }
        }
    }

    private fun performShare(isVector: Boolean) {
        showProgress()

        scope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "Share_$timestamp.pdf"
                val file = File(context.cacheDir, fileName)
                val scale =
                    com.alexdremov.notate.data.PreferencesManager
                        .getPdfExportScale(context)

                // Write to temp file
                file.outputStream().use { outputStream ->
                    PdfExporter.export(
                        context,
                        modelProvider(),
                        outputStream,
                        isVector,
                        object : PdfExporter.ProgressCallback {
                            override fun onProgress(
                                progress: Int,
                                message: String,
                            ) {
                                scope.launch(Dispatchers.Main) {
                                    updateProgress(progress, message)
                                }
                            }
                        },
                        bitmapScale = scale,
                    )
                }

                // Share
                val shareUri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        file,
                    )

                val shareIntent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                withContext(Dispatchers.Main) {
                    hideProgress()
                    context.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    Logger.e("Export", "Share Failed: ${e.localizedMessage}", e, showToUser = true)
                }
            }
        }
    }

    private fun showProgress() {
        if (progressDialog == null) {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
            tvProgressMessage = view.findViewById(R.id.tv_progress_message)
            progressBar = view.findViewById(R.id.progressBar)

            progressDialog =
                AlertDialog
                    .Builder(context)
                    .setView(view)
                    .setCancelable(false)
                    .create()
        }
        progressBar?.progress = 0
        tvProgressMessage?.text = "Starting..."
        progressDialog?.show()
    }

    private fun updateProgress(
        progress: Int,
        message: String,
    ) {
        progressBar?.progress = progress
        tvProgressMessage?.text = message
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
    }
}
