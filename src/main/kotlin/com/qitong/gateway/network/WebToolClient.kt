/*
 * ★ v1.188 沙盒网络工具专用 HTTP 客户端 —— 根治「Agent 卡死」
 *
 * 根因：web_search/web_browse 用 UpstreamClient（readTimeout 60s / callTimeout 0 无限）
 * 或每次 new OkHttpClient()（默认 readTimeout=0 无限），目标站挂起时线程永久阻塞，
 * 外层 withTimeout(35s) 取消不了同步阻塞 IO → Agent 循环永远等不到结果 → 卡死。
 *
 * 方案：统一短超时（connect 8s / read 15s / callTimeout 20s），网络工具 20s 内必返回，
 * 配合 executeSuspend 的 35s withTimeout，任何工具 35s 内必结束，Agent 永不卡死。
 */
package com.qitong.gateway.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object WebToolClient {

    /** 沙盒网页类工具专用客户端（短超时，杜绝同步阻塞卡线程） */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)   // 总超时 20s：任何页面/搜索 20s 内必须返回
        .retryOnConnectionFailure(false)      // 不重试，失败快速回落，避免叠加超时
        .build()
}