package com.qitong.gateway.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64

/**
 * v1.166 两步验证（2FA/TOTP，RFC6238 标准，纯自研不依赖外部库）
 * 生成密钥 → 提供 otpauth URI（用户用身份验证器/微信小程序扫）→ 校验 6 位动态码
 */
object Totp {
    private const val ALGO = "HmacSHA1"
    private const val DIGITS = 6
    private const val PERIOD = 30L
    private val random = SecureRandom()

    /** 生成 16 字节随机密钥（Base32 编码，无填充） */
    fun generateSecret(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return base32Encode(bytes).trimEnd('=')
    }

    /** 生成 otpauth:// 链接（供扫码绑定） */
    fun otpauthUri(secret: String, account: String, issuer: String = "QiTongGateway"): String {
        return "otpauth://totp/$issuer:$account?secret=$secret&issuer=$issuer&digits=$DIGITS&period=$PERIOD"
    }

    /** 校验用户输入的 6 位码（当前/前一/后一 时间窗，容忍时钟偏差） */
    fun verify(secret: String, code: String): Boolean {
        if (secret.isBlank() || code.isBlank()) return false
        val c = code.trim()
        if (c.length != DIGITS || !c.all { it.isDigit() }) return false
        val now = System.currentTimeMillis() / 1000
        val step = now / PERIOD
        // 允许 ±1 时间窗（共 90s 有效）
        for (offset in -1L..1L) {
            if (generateCode(secret, step + offset) == c) return true
        }
        return false
    }

    /** 计算 TOTP 动态码（RFC 6238） */
    private fun generateCode(secret: String, counter: Long): String {
        val key = base32Decode(secret)
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(key, ALGO))
        val msg = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) { msg[i] = (v and 0xff).toByte(); v = v ushr 8 }
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val bin = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val mod = bin % 1_000_000
        return mod.toString().padStart(DIGITS, '0')
    }

    // ===== Base32（RFC 4648，无填充） =====
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(B32[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) sb.append(B32[(buffer shl (5 - bitsLeft)) and 0x1f])
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray {
        val clean = s.uppercase().filter { it in B32 }
        var buffer = 0
        var bitsLeft = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in clean) {
            buffer = (buffer shl 5) or (B32.indexOf(ch) and 0x1f)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xff)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}