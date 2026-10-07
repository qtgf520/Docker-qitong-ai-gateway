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
            // ★ v1.107 万人并发转发：连接池 5→200、每host并发 5→200、Dispatcher 256 线程（顶得住高并发）
            .connectionPool(ConnectionPool(200, 60, TimeUnit.SECONDS))
            .dispatcher(okhttp3.Dispatcher(java.util.concurrent.Executors.newFixedThreadPool(256)).apply {
                maxRequests = 512
                maxRequestsPerHost = 200
            })

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
            // ★ v1.107 万人并发转发：连接池 5→200、每host并发 5→200、Dispatcher 256 线程
            .connectionPool(ConnectionPool(200, 60, TimeUnit.SECONDS))
            .dispatcher(okhttp3.Dispatcher(java.util.concurrent.Executors.newFixedThreadPool(256)).apply {
                maxRequests = 512
                maxRequestsPerHost = 200
            })
            .build()
    }

    /** 根据模型是否走代理返回客户端 */
    fun getClient(useProxy: Boolean): OkHttpClient =
        if (useProxy) client else directClient

    /** ★ v1.109 非流式请求客户端（readTimeout 60s 兜底，防上游挂起卡死「调佣不回复」）；流式用上面无限超时的 client */
    @Volatile
    private var clientShort: OkHttpClient? = null
    @Volatile
    private var directShort: OkHttpClient? = null

    fun getClient(useProxy: Boolean, stream: Boolean): OkHttpClient {
        if (stream) return getClient(useProxy)
        if (useProxy) {
            if (clientShort == null) clientShort = createClient().newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
            return clientShort!!
        }
        if (directShort == null) directShort = createDirectClient().newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
        return directShort!!
    }

    /** 获取默认客户端 */
    fun getOkHttpClient(): OkHttpClient = client
}