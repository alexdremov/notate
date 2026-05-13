package com.alexdremov.notate.data

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.GetLastModified
import at.bitfire.dav4jvm.property.ResourceType
import com.alexdremov.notate.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class WebDavProvider(
    private val config: RemoteStorageConfig,
    private val password: String,
    client: OkHttpClient? = null,
) : RemoteStorageProvider {
    private val client: OkHttpClient = client ?: createDefaultClient(config, password)

    companion object {
        private fun createDefaultClient(
            config: RemoteStorageConfig,
            password: String,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // Increased to allow slow uploads
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    val request =
                        chain
                            .request()
                            .newBuilder()
                            .header("Authorization", Credentials.basic(config.username ?: "", password))
                            .build()
                    chain.proceed(request)
                }.build()
    }

    private fun getBaseUrl(): HttpUrl {
        val base = config.baseUrl?.trimEnd('/') ?: ""
        if (base.isEmpty()) throw IllegalArgumentException("Base URL is empty")

        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw IllegalArgumentException("Base URL must include an explicit scheme (http:// or https://)")
        }

        val url = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid base URL: $base")
        return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
    }

    private fun buildUrl(
        remotePath: String,
        isDirectory: Boolean = false,
    ): HttpUrl {
        val base = getBaseUrl()
        val path = remotePath.trimStart('/')
        if (path.isEmpty()) return base

        val finalPath = if (isDirectory && !path.endsWith("/")) "$path/" else path
        return base.resolve(finalPath) ?: throw IllegalArgumentException("Invalid path: $finalPath")
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFile> =
        withContext(Dispatchers.IO) {
            val url = buildUrl(remotePath, isDirectory = true)
            Logger.d("WebDavProvider", "PROPFIND $url")
            val resource = DavResource(client, url)
            val files = mutableListOf<RemoteFile>()

            try {
                resource.propfind(1, GetLastModified.NAME, GetContentLength.NAME, ResourceType.NAME) { response, _ ->
                    val href = response.href

                    val requestSegments = url.pathSegments.filter { it.isNotEmpty() }
                    val responseSegments = href.pathSegments.filter { it.isNotEmpty() }

                    // Skip the parent directory itself
                    if (requestSegments != responseSegments && responseSegments.isNotEmpty()) {
                        val name = responseSegments.lastOrNull() ?: ""

                        val lm = response.properties.filterIsInstance<GetLastModified>().firstOrNull()
                        val lastModified = lm?.lastModified ?: 0L

                        val cl = response.properties.filterIsInstance<GetContentLength>().firstOrNull()
                        val size = cl?.contentLength ?: 0L

                        val rt = response.properties.filterIsInstance<ResourceType>().firstOrNull()
                        val isDirectory = rt?.types?.contains(ResourceType.COLLECTION) == true

                        files.add(
                            RemoteFile(
                                name = name,
                                path = href.toString(),
                                lastModified = lastModified,
                                size = size,
                                isDirectory = isDirectory,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                if (msg.contains("404")) {
                    throw FileNotFoundException("Remote path not found: $remotePath")
                }
                throw IOException("WebDAV error: $msg", e)
            }

            files
        }

    override suspend fun uploadFile(
        remotePath: String,
        inputStream: InputStream,
        size: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val url = buildUrl(remotePath)
            Logger.d("WebDavProvider", "Starting upload to $url (size: $size)")

            val body =
                object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()

                    override fun contentLength(): Long = if (size >= 0) size else super.contentLength()

                    override fun isOneShot(): Boolean = true

                    override fun writeTo(sink: BufferedSink) {
                        try {
                            Logger.d("WebDavProvider", "Streaming data for $url")
                            val startTime = System.currentTimeMillis()
                            var bytesWritten = 0L

                            val buffer = ByteArray(8192)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                                bytesWritten += read

                                // Debug log every 5MB
                                if (bytesWritten % (5 * 1024 * 1024) < 8192 && bytesWritten > 0) {
                                    Logger.d("WebDavProvider", "Upload progress: ${bytesWritten / 1024} KB / ${size / 1024} KB")
                                }
                            }

                            val duration = System.currentTimeMillis() - startTime
                            Logger.d("WebDavProvider", "Finished streaming $bytesWritten bytes in $duration ms")
                        } finally {
                            // Close the inputStream as soon as we are done reading it.
                            // This is important for SAF-backed streams (like Google Drive) to release
                            // underlying resources/locks while we wait for the HTTP response.
                            try {
                                inputStream.close()
                            } catch (e: Exception) {
                                Logger.w("WebDavProvider", "Error closing inputStream in writeTo", e)
                            }
                        }
                    }
                }

            val request =
                Request
                    .Builder()
                    .url(url)
                    // Do not force "Expect: 100-continue":
                    // some HTTP/2 WebDAV deployments (e.g. behind Nginx) may stall PUT uploads
                    // when this header is sent unconditionally.
                    .put(body)
                    .build()

            try {
                client.newCall(request).execute().use { response ->
                    Logger.d("WebDavProvider", "Upload response code for $url: ${response.code}")
                    if (!response.isSuccessful) {
                        Logger.w("WebDavProvider", "Upload failed with code ${response.code}: ${response.message}")
                    }
                    response.isSuccessful
                }
            } catch (e: Exception) {
                Logger.e("WebDavProvider", "Exception during upload to $url", e)
                false
            }
        }

    override suspend fun downloadFile(remotePath: String): InputStream? =
        withContext(Dispatchers.IO) {
            val url = buildUrl(remotePath)
            Logger.d("WebDavProvider", "GET $url")
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Logger.w("WebDavProvider", "Download failed with code ${response.code}: ${response.message}")
                    response.close()
                    return@withContext null
                }
                // The caller is responsible for closing the returned stream,
                // which will also close the response body.
                response.body?.byteStream()
            } catch (e: Exception) {
                Logger.e("WebDavProvider", "Exception during download from $url", e)
                null
            }
        }

    override suspend fun createDirectory(remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val parts = remotePath.split('/').filter { it.isNotEmpty() }
            var currentPath = ""

            for (part in parts) {
                currentPath += "$part/"
                val url = buildUrl(currentPath, isDirectory = true)
                val resource = DavResource(client, url)

                try {
                    var exists = false
                    try {
                        resource.propfind(0, ResourceType.NAME) { _, _ -> exists = true }
                    } catch (e: Exception) {
                        exists = false
                    }

                    if (!exists) {
                        Logger.d("WebDavProvider", "MKCOL $url")
                        val request =
                            Request
                                .Builder()
                                .url(url)
                                .method("MKCOL", null)
                                .build()
                        val success =
                            client.newCall(request).execute().use { response ->
                                response.isSuccessful || response.code == 405
                            }
                        if (!success) return@withContext false
                    }
                } catch (e: Exception) {
                    return@withContext false
                }
            }
            true
        }

    override suspend fun deleteFile(remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = buildUrl(remotePath)
            Logger.d("WebDavProvider", "DELETE $url")
            val request =
                Request
                    .Builder()
                    .url(url)
                    .delete()
                    .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 404
            }
        }

    override suspend fun testConnection(): Boolean =
        withContext(Dispatchers.IO) {
            val url = getBaseUrl()
            Logger.d("WebDavProvider", "Testing connection to $url")
            val resource = DavResource(client, url)
            try {
                resource.propfind(0, ResourceType.NAME) { _, _ -> }
                true
            } catch (e: Exception) {
                Logger.e("WebDavProvider", "Connection test failed", e)
                throw IOException("WebDAV error: ${e.message}", e)
            }
        }
}
