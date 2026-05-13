package com.alexdremov.notate.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDavProviderIntegrationTest {
    private val activeContainers = mutableListOf<GenericContainer<*>>()

    private data class WebDavServerSpec(
        val scheme: String,
        val containerPort: Int,
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
                containerPort = 80,
                env =
                    mapOf(
                        "AUTH_TYPE" to "Basic",
                        "USERNAME" to "user",
                        "PASSWORD" to "pass",
                        "LOCATION" to "/webdav",
                    ),
            ),
            insecureTls = false,
        )

    @Test
    fun `full WebDAV provider interface works against real HTTPS server`() =
        runAgainstServer(
            WebDavServerSpec(
                scheme = "https",
                containerPort = 443,
                env =
                    mapOf(
                        "AUTH_TYPE" to "Basic",
                        "USERNAME" to "user",
                        "PASSWORD" to "pass",
                        "LOCATION" to "/webdav",
                        "SSL_CERT" to "selfsigned",
                    ),
            ),
            insecureTls = true,
        )

    private fun runAgainstServer(
        spec: WebDavServerSpec,
        insecureTls: Boolean,
    ) = runTest {
        assumeTrue(
            "Docker is required for real WebDAV integration tests",
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
        )

        val container = GenericContainer("bytemark/webdav:2.4")
        spec.env.forEach { (k, v) -> container.withEnv(k, v) }
        container.withExposedPorts(spec.containerPort)
        container.start()
        activeContainers.add(container)

        val baseUrl = "${spec.scheme}://${container.host}:${container.getMappedPort(spec.containerPort)}/webdav/"
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

        assertThrows(FileNotFoundException::class.java) {
            runBlocking {
                provider.listFiles("sync-root/does-not-exist")
            }
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
            // Test-only trust manager:
            // this accepts the container's self-signed certificate so we can verify WebDAV
            // behavior over HTTPS in integration tests. Never use this in production.
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

            // Prefer TLSv1.3 in tests, falling back to TLSv1.2 for environments where
            // TLSv1.3 is unavailable.
            val sslContext =
                runCatching { SSLContext.getInstance("TLSv1.3") }
                    .getOrElse { SSLContext.getInstance("TLSv1.2") }
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            clientBuilder.sslSocketFactory(sslContext.socketFactory, trustManager)
            // Test-only hostname verifier for localhost/container certificates.
            // This intentionally disables hostname checks for this isolated test setup.
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
