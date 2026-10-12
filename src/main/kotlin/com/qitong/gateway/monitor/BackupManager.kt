package com.qitong.gateway.monitor

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * ★ v1.124 备份/快照模块 —— SQLite 在线一致性备份 + 配置导出
 * - 备份：VACUUM INTO（SQLite 在线一致性备份，不锁库）
 * - 恢复：替换数据库文件（重启生效）
 * - 保留策略：默认保留最近 10 份，自动清理更早
 */
object BackupManager {

    @Volatile var backupDir: String = "data/backups"

    private fun now(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())

    /** 执行一次 SQLite 在线备份，返回备份文件路径 */
    fun backup(databaseUrl: String): String {
        val dir = File(backupDir).apply { mkdirs() }
        val target = File(dir, "gateway-backup-${now()}.db")
        // VACUUM INTO：SQLite 内建在线一致性备份到新文件（不锁库，不影响服务）
        try {
            val conn = java.sql.DriverManager.getConnection(databaseUrl)
            try {
                conn.createStatement().use { st -> st.execute("VACUUM INTO '${target.absolutePath.replace("'", "''")}'") }
            } finally { conn.close() }
        } catch (e: Exception) {
            // 兜底：直接复制数据库文件（SQLite WAL 模式下也基本一致）
            val src = File(databaseUrl.removePrefix("jdbc:sqlite:"))
            if (src.exists()) Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        cleanup()
        return target.absolutePath
    }

    /** 列出所有备份 */
    fun listBackups(): List<Map<String, Any?>> {
        val dir = File(backupDir)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                mapOf(
                    "name" to f.name,
                    "sizeMB" to String.format(java.util.Locale.US, "%.2f", f.length() / 1024.0 / 1024.0),
                    "time" to f.lastModified()
                )
            } ?: emptyList()
    }

    /** 恢复备份：拷贝指定备份文件为当前库（调用方需确认目标路径） */
    fun restore(backupName: String, databasePath: String): Boolean {
        val src = File(backupDir, backupName)
        if (!src.exists() || !src.name.endsWith(".db")) return false
        val dst = File(databasePath)
        dst.parentFile?.mkdirs()
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /** 清理：保留最近 keep（默认10）份 */
    private fun cleanup(keep: Int = 10) {
        val dir = File(backupDir)
        if (!dir.exists()) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("gateway-backup-") }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > keep) {
            files.drop(keep).forEach { it.delete() }
        }
    }
}