package com.alexdremov.notate.data

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDavProviderIntegrationTest {
    private val activeContainers = mutableListOf<GenericContainer<*>>()

    companion object {
        private const val RCLONE_IMAGE = "rclone/rclone:latest"

        private fun findWebDavResource(filename: String): File? {
            var current: File? = File(System.getProperty("user.dir")).absoluteFile
            println("Searching for $filename starting from: ${current?.absolutePath}")

            while (current != null) {
                val potential = File(current, "webdav/$filename")
                if (potential.exists()) {
                    println("Found $filename at ${potential.absolutePath}")
                    return potential
                }
                current = current.parentFile
            }

            println("Failed to find $filename in any parent directory.")
            return null
        }

        private fun isCi(): Boolean = System.getenv("GITHUB_ACTIONS") == "true"
    }

    private data class WebDavServerSpec(
        val scheme: String,
        val containerPort: Int,
        val locationPath: String,
        val env: Map<String, String>,
    )

    @After
    fun tearDown() {
        activeContainers.asReversed().forEach {
            runCatching { it.stop() }
        }
        activeContainers.clear()
    }

    @Test
    fun `full WebDAV provider interface works against real HTTP server`() =
        runAgainstServer(
            WebDavServerSpec(
                scheme = "http",
                containerPort = 8080,
                locationPath = "/webdav/",
                env =
                    mapOf(
                        "USERNAME" to "user",
                        "PASSWORD" to "pass",
                    ),
            ),
            insecureTls = false,
        )

    @Test
    fun `full WebDAV provider interface works against real HTTPS server`() =
        runAgainstServer(
            WebDavServerSpec(
                scheme = "https",
                containerPort = 8443,
                locationPath = "/webdav/",
                env =
                    mapOf(
                        "USERNAME" to "user",
                        "PASSWORD" to "pass",
                    ),
            ),
            insecureTls = true,
        )

    private fun runAgainstServer(
        spec: WebDavServerSpec,
        insecureTls: Boolean,
    ) = runBlocking {
        assumeTrue("Skipping brittle container-based tests in CI", !isCi())

        assumeTrue(
            "Docker is required for real WebDAV integration tests",
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
        )

        val container = GenericContainer(RCLONE_IMAGE)
        val rcloneCmd =
            mutableListOf(
                "serve",
                "webdav",
                "/data",
                "--addr",
                "0.0.0.0:${spec.containerPort}",
                "--user",
                spec.env["USERNAME"]!!,
                "--pass",
                spec.env["PASSWORD"]!!,
                "--baseurl",
                spec.locationPath,
                "-vv",
            )

        if (spec.scheme == "https") {
            // Mount certs from the repo root
            val certFile = findWebDavResource("cert.pem")
            val keyFile = findWebDavResource("key.pem")

            if (certFile != null && keyFile != null) {
                container.withFileSystemBind(certFile.absolutePath, "/certs/cert.pem", BindMode.READ_ONLY)
                container.withFileSystemBind(keyFile.absolutePath, "/certs/key.pem", BindMode.READ_ONLY)
                rcloneCmd.add("--cert")
                rcloneCmd.add("/certs/cert.pem")
                rcloneCmd.add("--key")
                rcloneCmd.add("/certs/key.pem")
            } else {
                System.err.println("Warning: cert.pem or key.pem not found. HTTPS test will likely fail with SSLException.")
            }
        }

        container.withCommand(*rcloneCmd.toTypedArray())
        container.withExposedPorts(spec.containerPort)

        val waitStrategy =
            Wait
                .forHttp(spec.locationPath)
                .withMethod("OPTIONS")
                .allowInsecure()
                .forStatusCodeMatching { it == 200 || it == 401 || it == 405 }

        if (spec.scheme == "https") {
            waitStrategy.usingTls()
        }

        container.waitingFor(waitStrategy)

        try {
            container.start()
        } catch (e: Exception) {
            System.err.println("Container failed to start for ${spec.scheme}. Logs:")
            System.err.println(container.logs)
            throw e
        }
        activeContainers.add(container)

        val baseUrl = "${spec.scheme}://${container.host}:${container.getMappedPort(spec.containerPort)}${spec.locationPath}"
        val provider = createProvider(baseUrl, "user", "pass", insecureTls)
        val initialData = "notate-webdav-content-v1".toByteArray()
        val updatedData = "notate-webdav-content-v2".toByteArray()

        assertTrue(provider.testConnection())
        assertTrue(provider.createDirectory("sync-root/alpha/beta"))
        assertTrue(provider.createDirectory("sync-root/alpha/beta")) // idempotent

        val alphaItems = provider.listFiles("sync-root/alpha")
        assertTrue(alphaItems.any { it.name == "beta" && it.isDirectory })

        val remoteFilePath = "sync-root/alpha/beta/test.notate"
        assertTrue(provider.uploadFile(remoteFilePath, ByteArrayInputStream(initialData), initialData.size.toLong()))

        val listed = provider.listFiles("sync-root/alpha/beta")
        val listedFile = listed.find { it.name == "test.notate" && !it.isDirectory }
        assertNotNull(listedFile)
        assertTrue((listedFile?.size ?: 0L) > 0L)

        val downloaded = provider.downloadFile(remoteFilePath)?.use { it.readBytes() }
        assertArrayEquals(initialData, downloaded)

        assertTrue(provider.uploadFile(remoteFilePath, ByteArrayInputStream(updatedData), updatedData.size.toLong()))
        val downloadedUpdated = provider.downloadFile(remoteFilePath)?.use { it.readBytes() }
        assertArrayEquals(updatedData, downloadedUpdated)

        assertTrue(provider.deleteFile(remoteFilePath))
        assertNull(provider.downloadFile(remoteFilePath))
        assertTrue(provider.deleteFile(remoteFilePath)) // 404 accepted

        try {
            provider.listFiles("sync-root/does-not-exist")
            error("Expected FileNotFoundException when listing non-existent directory sync-root/does-not-exist")
        } catch (e: FileNotFoundException) {
            // expected
        }
    }

    private fun createProvider(
        baseUrl: String,
        username: String,
        password: String,
        insecureTls: Boolean,
    ): WebDavProvider {
        val config =
            RemoteStorageConfig(
                id = "real-webdav",
                name = "Real WebDAV",
                type = RemoteStorageType.WEBDAV,
                baseUrl = baseUrl,
                username = username,
            )

        val clientBuilder =
            OkHttpClient
                .Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header("Authorization", Credentials.basic(username, password))
                            .build(),
                    )
                }

        if (insecureTls) {
            val trustManager =
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<X509Certificate>,
                        authType: String,
                    ) {
                    }

                    override fun checkServerTrusted(
                        chain: Array<X509Certificate>,
                        authType: String,
                    ) {
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }

            val sslContext =
                runCatching { SSLContext.getInstance("TLSv1.3") }
                    .getOrElse { SSLContext.getInstance("TLSv1.2") }
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            clientBuilder.sslSocketFactory(sslContext.socketFactory, trustManager)
            clientBuilder.hostnameVerifier(
                object : HostnameVerifier {
                    override fun verify(
                        hostname: String?,
                        session: SSLSession?,
                    ): Boolean = true
                },
            )
        }

        return WebDavProvider(config, password, clientBuilder.build())
    }
}
