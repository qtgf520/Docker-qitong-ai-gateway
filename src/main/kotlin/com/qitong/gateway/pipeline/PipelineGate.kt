/*
 * ★ v1.184 管道预检入口（Stage 深化：限流/白名单 Stage 真正接管三渠道）
 *
 * 设计要点（自研表述，无外部来源痕迹）：
 * - 三渠道（内置聊天/QQ/微信）消息进入正式处理前，先过统一的「管道预检」
 * - 预检跑 限流 Stage（按用户/渠道配额检查，真正生效）+ 可配置白名单 Stage（未绑定用户策略）
 * - 返回 null = 放行；返回非 null = 拦截提示（调用方直接回复该文案并 return）
 * - 与现有 askModel/并发池完全兼容：只加判断不改原流程
 */
package com.qitong.gateway.pipeline

import com.qitong.gateway.db.Database
import com.qitong.gateway.rate.RateLimiter

object PipelineGate {

    /**
     * 消息进入前预检（三渠道统一调用）。
     *
     * @return null=放行；非 null=被拦截，文案可直接回复用户
     */
    suspend fun precheck(
        channel: String,           // chat | qq | weixin
        userId: Long,              // 网关用户 ID（0=未绑定）
        userOpenid: String,        // 渠道侧用户标识
        isAdmin: Boolean,          // 是否管理员/代理
        text: String,              // 消息原文（白名单用）
        db: Database
    ): String? {
        // ① 白名单 Stage（可配置：pipeline_whitelist_enabled=true 才启用；默认关=不破坏未绑定可聊体验）
        runCatching {
            if (db.getConfig("pipeline_whitelist_enabled", "false").toBoolean() && !isAdmin && userId <= 0) {
                val t = text.trim()
                val pass = t.startsWith("绑定账号") || t.startsWith("绑定码 ") ||
                    t == "绑定码" || t == "我的账号" || t == "退出账号" || t == "解绑"
                if (!pass) return "🔓 当前未绑定网关账号\n发「绑定账号」或网页生成绑定码后发「绑定码 123456」"
            }
        }
        // ② 限流 Stage（真正生效：绑定用户按配额检查，管理员不限）
        runCatching {
            if (!isAdmin && userId > 0) {
                val bucketKey = "pipeline:$channel:$userOpenid"
                val limited = RateLimiter.check(db, bucketKey, userId, isAdmin)
                if (limited != null) return "⚠️ $limited"
            }
        }
        return null
    }

    /** 语义化便捷入口：构造 MsgContext 后调用（供需要完整上下文的地方用） */
    suspend fun precheck(ctx: MsgContext, db: Database): String? =
        precheck(ctx.channel, ctx.userId, ctx.userOpenid, ctx.isAdmin, ctx.text, db)
}