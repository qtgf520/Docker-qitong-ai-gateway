#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# v1.157 2FA：users 表迁移 + rowToUser 映射 + 2FA 方法
import io

P = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
d = io.open(P, encoding="utf-8").read()

# 1) 迁移：plan_expire_at 后加 totp_secret
a1 = "try { st.executeUpdate(\"ALTER TABLE users ADD COLUMN plan_expire_at INTEGER NOT NULL DEFAULT 0\") } catch (_: Exception) {}"
n1 = a1 + '\n            try { st.executeUpdate("ALTER TABLE users ADD COLUMN totp_secret TEXT NOT NULL DEFAULT \'\'") } catch (_: Exception) {}'
assert d.count(a1) == 1, f"迁移 anchor miss {d.count(a1)}"
d = d.replace(a1, n1)
print("迁移 OK")

# 2) rowToUser 加 totpSecret（planExpireAt 后补逗号+字段）
a2 = "        planExpireAt = (it[\"plan_expire_at\"] as? Number)?.toLong() ?: 0\n    )"
n2 = "        planExpireAt = (it[\"plan_expire_at\"] as? Number)?.toLong() ?: 0,\n        totpSecret = it[\"totp_secret\"] as? String ?: \"\"\n    )"
assert d.count(a2) == 1, f"rowToUser anchor miss {d.count(a2)}"
d = d.replace(a2, n2)
print("rowToUser OK")

# 3) 加 2FA 方法：setTotpSecret / clearTotpSecret（放 updateUserEmail 后）
a3 = "    fun updateUserEmail(userId: Long, email: String, notifyEnabled: Boolean) {\n        stmt(\"UPDATE users SET email=?, notify_enabled=? WHERE id=?\", email, if (notifyEnabled) 1 else 0, userId)\n    }"
n3 = a3 + '\n\n    /** ★ v1.157 两步验证：设置/清除 TOTP 密钥 */\n    fun setTotpSecret(userId: Long, secret: String) {\n        stmt("UPDATE users SET totp_secret=? WHERE id=?", secret, userId)\n    }\n    fun getTotpSecret(userId: Long): String = getUserById(userId)?.totpSecret ?: ""'
assert d.count(a3) == 1, f"2FA 方法 anchor miss {d.count(a3)}"
d = d.replace(a3, n3)
print("2FA 方法 OK")

io.open(P, "w", encoding="utf-8").write(d)
print("Database OK")