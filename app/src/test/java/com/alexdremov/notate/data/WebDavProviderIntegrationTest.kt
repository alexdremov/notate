package com.alexdremov.notate.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.Buffer
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WebDavProviderIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var dispatcher: InMemoryWebDavDispatcher

    @Before
    fun setup() {
        server = MockWebServer()
        dispatcher = InMemoryWebDavDispatcher()
        server.dispatcher = dispatcher
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `webdav operations work over HTTP_1_1 without Expect header`() =
        runWebDavRoundtripTest(listOf(Protocol.HTTP_1_1))

    @Test
    fun `webdav operations work over HTTP_2 prior knowledge without Expect header`() =
        runWebDavRoundtripTest(listOf(Protocol.H2_PRIOR_KNOWLEDGE))

    private fun runWebDavRoundtripTest(protocols: List<Protocol>) =
        runTest {
            server.protocols = protocols
            server.start()

            val provider = createProvider(protocols)
            val payload = "notate-webdav-sync".toByteArray()

            assertTrue(provider.createDirectory("sync-root/subdir"))
            assertTrue(
                provider.uploadFile(
                    "sync-root/subdir/test.notate",
                    ByteArrayInputStream(payload),
                    payload.size.toLong(),
                ),
            )

            val listed = provider.listFiles("sync-root/subdir")
            assertTrue(listed.any { it.name == "test.notate" && !it.isDirectory && it.size == payload.size.toLong() })

            val downloaded = provider.downloadFile("sync-root/subdir/test.notate")!!.use { it.readBytes() }
            assertArrayEquals(payload, downloaded)

            assertTrue(provider.deleteFile("sync-root/subdir/test.notate"))
            assertFalse(provider.listFiles("sync-root/subdir").any { it.name == "test.notate" })
            assertTrue(dispatcher.putExpectHeaders.all { it.isNullOrBlank() })
        }

    private fun createProvider(protocols: List<Protocol>): WebDavProvider {
        val config =
            RemoteStorageConfig(
                id = "storage-id",
                name = "Test WebDAV",
                type = RemoteStorageType.WEBDAV,
                baseUrl = server.url("/webdav/").toString(),
                username = "user",
            )

        val client =
            OkHttpClient
                .Builder()
                .protocols(protocols)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder().header("Authorization", "Basic dXNlcjpwYXNz").build(),
                    )
                }.build()

        return WebDavProvider(config, "pass", client)
    }

    private class InMemoryWebDavDispatcher : Dispatcher() {
        private val directories = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val files = ConcurrentHashMap<String, ByteArray>()
        val putExpectHeaders = java.util.Collections.synchronizedList(mutableListOf<String?>())

        init {
            directories.add("/")
            directories.add("/webdav")
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = normalizePath(request.path ?: "/")

            return when (request.method) {
                "MKCOL" -> handleMkcol(path)
                "PUT" -> handlePut(path, request)
                "GET" -> handleGet(path)
                "DELETE" -> handleDelete(path)
                "PROPFIND" -> handlePropfind(path, request.getHeader("Depth"))
                else -> MockResponse().setResponseCode(HttpURLConnection.HTTP_BAD_METHOD)
            }
        }

        private fun handleMkcol(path: String): MockResponse {
            val parent = parent(path)
            if (!directories.contains(parent)) {
                return MockResponse().setResponseCode(HttpURLConnection.HTTP_CONFLICT)
            }
            directories.add(path)
            return MockResponse().setResponseCode(HttpURLConnection.HTTP_CREATED)
        }

        private fun handlePut(
            path: String,
            request: RecordedRequest,
        ): MockResponse {
            putExpectHeaders.add(request.getHeader("Expect"))
            val parent = parent(path)
            if (!directories.contains(parent)) {
                return MockResponse().setResponseCode(HttpURLConnection.HTTP_CONFLICT)
            }
            files[path] = request.body.readByteArray()
            return MockResponse().setResponseCode(HttpURLConnection.HTTP_CREATED)
        }

        private fun handleGet(path: String): MockResponse {
            val data = files[path] ?: return MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
            return MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(Buffer().write(data))
        }

        private fun handleDelete(path: String): MockResponse {
            files.remove(path)
            directories.remove(path)
            return MockResponse().setResponseCode(HttpURLConnection.HTTP_NO_CONTENT)
        }

        private fun handlePropfind(
            path: String,
            depthHeader: String?,
        ): MockResponse {
            if (!directories.contains(path) && !files.containsKey(path)) {
                return MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
            }

            val depth =
                when {
                    depthHeader == null -> 0
                    depthHeader == "infinity" -> Int.MAX_VALUE
                    depthHeader.toIntOrNull() != null -> depthHeader.toInt()
                    else -> return MockResponse().setResponseCode(HttpURLConnection.HTTP_BAD_REQUEST)
                }
            val responses = mutableListOf(buildResponse(path, directories.contains(path), files[path]?.size?.toLong() ?: 0L))
            if (depth > 0 && directories.contains(path)) {
                listChildren(path).forEach { child ->
                    responses.add(buildResponse(child, directories.contains(child), files[child]?.size?.toLong() ?: 0L))
                }
            }

            val body =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                    ${responses.joinToString("\n")}
                </d:multistatus>
                """.trimIndent()

            return MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body)
        }

        private fun listChildren(directory: String): List<String> {
            val prefix = if (directory == "/") "/" else "$directory/"
            val childDirs =
                directories
                    .filter { it != directory && it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it.isNotEmpty() && !it.contains('/') }
                    .map { "$prefix$it" }
            val childFiles =
                files.keys
                    .filter { it.startsWith(prefix) }
                    .map { it.removePrefix(prefix) }
                    .filter { it.isNotEmpty() && !it.contains('/') }
                    .map { "$prefix$it" }
            return (childDirs + childFiles).distinct().sorted()
        }

        private fun buildResponse(
            absolutePath: String,
            isDirectory: Boolean,
            size: Long,
        ): String {
            val href = if (isDirectory) "$absolutePath/" else absolutePath
            val modified =
                DateTimeFormatter
                    .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(1710000000000L))

            return if (isDirectory) {
                """
                <d:response>
                    <d:href>$href</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getlastmodified>$modified</d:getlastmodified>
                            <d:getcontentlength>0</d:getcontentlength>
                            <d:resourcetype><d:collection/></d:resourcetype>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                """.trimIndent()
            } else {
                """
                <d:response>
                    <d:href>$href</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:getlastmodified>$modified</d:getlastmodified>
                            <d:getcontentlength>$size</d:getcontentlength>
                            <d:resourcetype/>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
                """.trimIndent()
            }
        }

        private fun normalizePath(rawPath: String): String {
            val noQuery = rawPath.substringBefore('?')
            val trimmed = if (noQuery.endsWith("/") && noQuery.length > 1) noQuery.dropLast(1) else noQuery
            return if (trimmed.isEmpty()) "/" else trimmed
        }

        private fun parent(path: String): String {
            if (path == "/") return "/"
            val idx = path.lastIndexOf('/')
            return if (idx <= 0) "/" else path.substring(0, idx)
        }
    }
}
