/*
 * ★ v1.178 事件总线 —— 把学习到的「事件驱动解耦」架构思想落进网关主项目。
 *
 * 设计要点（自研表述，无外部来源痕迹）：
 * - 消息收发/回复/工具调用/记忆存取等关键动作发布事件，各模块解耦
 * - 新增插件/能力只需订阅事件，无需改动核心流程
 * - 线程安全：订阅者按注册顺序执行，异常隔离（单个订阅者报错不影响其他）
 */
package com.qitong.gateway.pipeline

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 网关事件定义（事件名 + 参数载荷）。
 * 事件名约定：domain.action，如 message.received / message.replied / tool.executed
 */
object GatewayEventBus {

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>>()

    /** 订阅事件。返回取消订阅的 lambda（可安全重复调用）。 */
    fun subscribe(event: String, listener: (Map<String, Any?>) -> Unit): () -> Unit {
        val list = listeners.computeIfAbsent(event) { CopyOnWriteArrayList() }
        list.add(listener)
        return { list.remove(listener) }
    }

    /** 发布事件：同步执行所有订阅者。单个订阅者异常不影响其他。 */
    fun publish(event: String, payload: Map<String, Any?> = emptyMap()) {
        val list = listeners[event] ?: return
        for (l in list) {
            try {
                l(payload)
            } catch (e: Exception) {
                System.err.println("[EventBus] 事件 $event 订阅者异常: ${e.message}")
            }
        }
    }

    /** 是否有订阅者（供发布方判断是否值得构造 payload） */
    fun hasSubscribers(event: String): Boolean = listeners[event]?.isNotEmpty() == true

    /** 当前事件订阅数（调试用） */
    fun listenerCount(event: String): Int = listeners[event]?.size ?: 0

    /** 清空所有订阅（测试用） */
    fun clear() { listeners.clear() }

    // ============ 内置事件名常量 ============
    object Events {
        const val MESSAGE_RECEIVED = "message.received"   // 消息进入管道（payload: ctx/result）
        const val MESSAGE_REPLIED = "message.replied"     // 管道产出回复（payload: ctx/reply/toolCalls/rounds）
        const val TOOL_EXECUTED = "tool.executed"         // Agent 工具执行成功（payload: fn/args/result）
        const val MEMORY_SAVED = "memory.saved"           // 沉淀记忆（payload: userId/content/type/importance）
    }
}