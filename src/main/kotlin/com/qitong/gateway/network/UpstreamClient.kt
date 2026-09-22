package com.qitong.gateway.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 上游客户端 —— 支持 HTTP/HTTPS/SOCKS5 代理及账号密码认证
 * 对齐原APP UpstreamClient.kt（精简为纯JVM版）
 */
object UpstreamClient {

    @Volatile
    private var currentConfig: ProxyConfig? = null

    @Volatile
    private var client: OkHttpClient = createClient()

    data class ProxyConfig(
        val type: String = "HTTP",
        val host: String = "",
        val port: Int = 0,
        val username: String = "",
        val password: String = "",
        val enabled: Boolean = false
    ) {
        val isValid: Boolean get() = enabled && host.isNotBlank() && port > 0
    }

    private fun createClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))

        val config = currentConfig
        if (config != null && config.isValid) {
            when (config.type.uppercase()) {
                "HTTP", "HTTPS" -> {
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
                    builder.proxy(proxy)
                    if (config.username.isNotBlank()) {
                        builder.proxyAuthenticator { _, response ->
                            if (response.code == 407) {
                                val credential = okhttp3.Credentials.basic(config.username, config.password)
                                response.request.newBuilder()
                                    .header("Proxy-Authorization", credential)
                                    .build()
                            } else {
                                null
                            }
                        }
                    }
                    if (config.type.uppercase() == "HTTPS") {
                        try {
                            val trustAllCerts = arrayOf<javax.net.ssl.X509TrustManager>(object : javax.net.ssl.X509TrustManager {
                                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                            })
                            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
                            builder.hostnameVerifier { _, _ -> true }
                        } catch (_: Exception) { }
                    }
                }
                "SOCKS5", "SOCKS" -> {
                    val socketFactory = Socks5SocketFactory(
                        proxyHost = config.host,
                        proxyPort = config.port,
                        username = config.username,
                        password = config.password
                    )
                    builder.socketFactory(socketFactory)
                }
            }
        }
        return builder.build()
    }

    fun setProxy(config: ProxyConfig?) {
        currentConfig = config
        client = createClient()
    }

    fun getCurrentProxy(): ProxyConfig? = currentConfig

    private var directClient: OkHttpClient = createDirectClient()

    private fun createDirectClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()
    }

    /** 根据模型是否走代理返回客户端 */
    fun getClient(useProxy: Boolean): OkHttpClient =
        if (useProxy) client else directClient

    /** 获取默认客户端 */
    fun getOkHttpClient(): OkHttpClient = client
}