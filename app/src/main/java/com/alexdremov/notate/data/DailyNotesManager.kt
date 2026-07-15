package com.alexdremov.notate.data

import android.content.Context
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds or creates a "daily note" (a canvas named for the current date) inside a reserved
 * "Daily Notes" subfolder of the first configured project. v1 simplification: host = first project.
 */
object DailyNotesManager {
    const val DAILY_NOTES_FOLDER = "Daily Notes"

    /** Sanitizer-stable date name (no punctuation, so StorageUtils.getSafeFileName keeps it). */
    fun todayNoteName(now: Date = Date()): String = SimpleDateFormat("MMM d yyyy", Locale.US).format(now)

    /**
     * Returns the path of today's note in the first project, creating folder/note if needed.
     * Returns null if no project is configured or on failure.
     */
    suspend fun openOrCreateTodayNote(context: Context): String? =
        withContext(Dispatchers.IO) {
            val project =
                PreferencesManager.getProjects(context).firstOrNull() ?: run {
                    Logger.w("DailyNotes", "No project configured for daily notes")
                    return@withContext null
                }

            val repo = ProjectRepository(context, project.uri)
            val todayName = todayNoteName()

            try {
                var folderPath = findFolderPath(repo, DAILY_NOTES_FOLDER)
                if (folderPath == null) {
                    repo.createProject(DAILY_NOTES_FOLDER, null)
                    folderPath = findFolderPath(repo, DAILY_NOTES_FOLDER)
                }
                if (folderPath == null) {
                    Logger.e("DailyNotes", "Failed to create Daily Notes folder", showToUser = true)
                    return@withContext null
                }

                findNotePath(repo, folderPath, todayName)?.let { return@withContext it }

                val created =
                    repo.createCanvas(
                        todayName,
                        folderPath,
                        CanvasType.INFINITE,
                        0f,
                        0f,
                    )
                created ?: findNotePath(repo, folderPath, todayName)
            } catch (e: Exception) {
                Logger.e("DailyNotes", "Failed to open or create today's note", e, showToUser = true)
                null
            }
        }

    private fun findFolderPath(
        repo: ProjectRepository,
        name: String,
    ): String? = repo.getItems(null).firstOrNull { it is ProjectItem && it.name == name }?.path

    private fun findNotePath(
        repo: ProjectRepository,
        folderPath: String,
        noteName: String,
    ): String? = repo.getItems(folderPath).firstOrNull { it is CanvasItem && it.name == noteName }?.path
}
