package com.alexdremov.notate.data

import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class WebDavProvider(
    private val config: RemoteStorageConfig,
    private val password: String,
) : RemoteStorageProvider {
    private val client = OkHttpClient()
    private val auth = Credentials.basic(config.username ?: "", password)

    private fun buildUrl(remotePath: String): String {
        val base = config.baseUrl?.trimEnd('/') ?: ""
        val path = remotePath.trimStart('/')
        return "$base/$path"
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFile> {
        val url = buildUrl(remotePath)
        val body =
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                    <D:getlastmodified/>
                    <D:getcontentlength/>
                    <D:resourcetype/>
                </D:prop>
            </D:propfind>
            """.trimIndent().toRequestBody("text/xml".toMediaType())

        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .addHeader("Depth", "1")
                .method("PROPFIND", body)
                .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    throw java.io.FileNotFoundException("Remote path not found: $remotePath")
                }
                throw java.io.IOException("WebDAV error: ${response.code} ${response.message}")
            }
            return parsePropFindResponse(response.body?.byteStream() ?: return emptyList(), remotePath)
        }
    }

    private fun parsePropFindResponse(
        inputStream: InputStream,
        parentPath: String,
    ): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType
        var currentFile: MutableRemoteFile? = null
        var currentTag: String? = null

        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag.endsWith("response")) {
                        currentFile = MutableRemoteFile()
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text
                    currentFile?.let { file ->
                        when (currentTag?.removePrefix("D:")?.removePrefix("d:")) {
                            "href" -> {
                                file.path = text
                            }

                            "displayname" -> {
                                file.name = text
                            }

                            "getlastmodified" -> {
                                file.lastModified =
                                    try {
                                        dateFormat.parse(text)?.time ?: 0L
                                    } catch (e: Exception) {
                                        0L
                                    }
                            }

                            "getcontentlength" -> {
                                file.size = text.toLongOrNull() ?: 0L
                            }

                            "collection" -> {
                                file.isDirectory = true
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.endsWith("response")) {
                        currentFile?.let {
                            // Only add if it's not the parent directory itself
                            if (it.path != null && !it.path!!.endsWith(parentPath) && !it.path!!.endsWith(parentPath + "/")) {
                                files.add(it.toRemoteFile())
                            }
                        }
                        currentFile = null
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return files
    }

    override suspend fun uploadFile(
        remotePath: String,
        inputStream: InputStream,
    ): Boolean {
        val url = buildUrl(remotePath)
        val body =
            object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun writeTo(sink: okio.BufferedSink) {
                    inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .put(body)
                .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    override suspend fun downloadFile(remotePath: String): InputStream? {
        val url = buildUrl(remotePath)
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .get()
                .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        // Body will be closed by caller by closing the InputStream
        return response.body?.byteStream()
    }

    override suspend fun createDirectory(remotePath: String): Boolean {
        val parts = remotePath.split('/').filter { it.isNotEmpty() }
        var currentPath = ""
        for (part in parts) {
            currentPath += "/$part"
            if (!checkDirectoryExists(currentPath)) {
                val url = buildUrl(currentPath)
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .addHeader("Authorization", auth)
                        .method("MKCOL", null)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 405) { // 405 Method Not Allowed often means already exists
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun checkDirectoryExists(remotePath: String): Boolean {
        val url = buildUrl(remotePath)
        val body =
            """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop><D:resourcetype/></D:prop>
            </D:propfind>
            """.trimIndent().toRequestBody("text/xml".toMediaType())

        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .addHeader("Depth", "0")
                .method("PROPFIND", body)
                .build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun deleteFile(remotePath: String): Boolean {
        val url = buildUrl(remotePath)
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("Authorization", auth)
                .delete()
                .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private class MutableRemoteFile {
        var name: String? = null
        var path: String? = null
        var lastModified: Long = 0
        var size: Long = 0
        var isDirectory: Boolean = false

        fun toRemoteFile(): RemoteFile {
            // href is usually a URL-encoded path or full URL
            val decodedPath = path?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
            return RemoteFile(
                name = name ?: decodedPath.trimEnd('/').substringAfterLast('/').ifEmpty { decodedPath },
                path = decodedPath,
                lastModified = lastModified,
                size = size,
                isDirectory = isDirectory,
            )
        }
    }
}
