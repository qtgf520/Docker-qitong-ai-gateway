package com.qitong.gateway.sandbox

import java.util.Calendar
import java.util.TimeZone

/**
 * Agora CronExpression —— 标准 5 字段 cron 表达式（v84 移植自 https://github.com/newo-ether/Agora）
 *
 * 格式：`minute hour day-of-month month day-of-week`
 * 支持：星号、单值、列表（a,b）、范围（a-b）、步进（斜杠 n、a-b斜杠n、a斜杠n）
 * 星期：0–6（0=周日），7 也接受为周日。月和星期仅数字。
 * 当 day-of-month 和 day-of-week 都受限时，任一匹配即命中（标准 Vixie-cron OR 规则）。
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    /**
     * 返回严格晚于 [afterMillis] 的第一个匹配时刻（秒/毫秒归零）。
     * 搜索范围 8 年。逐日→逐时→逐分跳跃，避免 4.2M 分钟暴力扫描。
     */
    fun next(afterMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = afterMillis
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, 1) // strictly after
        }
        val deadline = cal.timeInMillis + HORIZON_MILLIS
        while (cal.timeInMillis <= deadline) {
            if (!dayMatches(cal)) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            if (cal.get(Calendar.HOUR_OF_DAY) !in hours) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                cal.set(Calendar.MINUTE, 0)
                continue
            }
            if (cal.get(Calendar.MINUTE) in minutes) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    /** 当日是否满足 月份 + dom/dow 约束 */
    private fun dayMatches(cal: Calendar): Boolean {
        if (cal.get(Calendar.MONTH) + 1 !in months) return false
        val dom = cal.get(Calendar.DAY_OF_MONTH) in daysOfMonth
        val dow = (cal.get(Calendar.DAY_OF_WEEK) - 1) in daysOfWeek // Calendar SUNDAY=1 → 0
        return when {
            domRestricted && dowRestricted -> dom || dow
            else -> dom && dow
        }
    }

    companion object {
        private const val HORIZON_MILLIS = 366L * 24 * 60 * 8 * 60_000L

        /** 解析 5 字段 cron；格式非法返回 null */
        fun parse(expr: String): CronExpression? {
            val parts = expr.trim().split(Regex("\\s+"))
            if (parts.size != 5) return null
            val minutes = parseField(parts[0], 0, 59) ?: return null
            val hours = parseField(parts[1], 0, 23) ?: return null
            val daysOfMonth = parseField(parts[2], 1, 31) ?: return null
            val months = parseField(parts[3], 1, 12) ?: return null
            val daysOfWeek = parseField(parts[4], 0, 7)?.map { if (it == 7) 0 else it }?.toSet() ?: return null
            return CronExpression(
                minutes, hours, daysOfMonth, months, daysOfWeek,
                domRestricted = parts[2].trim() != "*",
                dowRestricted = parts[4].trim() != "*",
            )
        }

        fun isValid(expr: String): Boolean = parse(expr) != null

        /** 展开单个字段：星号、a、a,b、a-b、斜杠n、a-b斜杠n、a斜杠n → 值集合 */
        private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
            val result = sortedSetOf<Int>()
            for (token in field.split(",")) {
                if (token.isEmpty()) return null
                val (rangePart, stepPart) = token.split("/").let {
                    when (it.size) {
                        1 -> it[0] to null
                        2 -> it[0] to it[1]
                        else -> return null
                    }
                }
                val step = stepPart?.toIntOrNull()?.takeIf { it > 0 } ?: if (stepPart == null) 1 else return null
                val (start, end) = when {
                    rangePart == "*" -> min to max
                    rangePart.contains("-") -> {
                        val r = rangePart.split("-")
                        if (r.size != 2) return null
                        val a = r[0].toIntOrNull() ?: return null
                        val b = r[1].toIntOrNull() ?: return null
                        a to b
                    }
                    else -> {
                        val v = rangePart.toIntOrNull() ?: return null
                        v to (if (stepPart != null) max else v)
                    }
                }
                if (start < min || end > max || start > end) return null
                var v = start
                while (v <= end) {
                    result.add(v)
                    v += step
                }
            }
            return result.ifEmpty { null }
        }
    }
}