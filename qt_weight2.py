#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# v1.156 模型权重 weight 第二阶段：迁移+rowToModel+addModel/updateModel
import io

P = "src/main/kotlin/com/qitong/gateway/db/Database.kt"
d = io.open(P, encoding="utf-8").read()

# 1) 迁移：在 supports_video 迁移行后加 weight 迁移
old_mig = 'try { st.executeUpdate("ALTER TABLE models ADD COLUMN supports_video INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}'
assert d.count(old_mig) == 1, f"mig anchor miss {d.count(old_mig)}"
new_mig = old_mig + '\n            try { st.executeUpdate("ALTER TABLE models ADD COLUMN weight INTEGER NOT NULL DEFAULT 50") } catch (_: Exception) {}'
d = d.replace(old_mig, new_mig)
print("迁移 OK")

# 2) rowToModel 加 weight 映射
old_row = '                "supportsVideo" to (row["supports_video"] as? Number)?.toInt() == 1'
if old_row in d:
    d = d.replace(old_row, old_row + ',\n                "weight" to ((row["weight"] as? Number)?.toInt() ?: 50)')
    print("rowToModel OK")
else:
    print("⚠️ rowToModel 未找到 supports_video 行，下面打印 rowToModel 段")
    idx = d.find("fun rowToModel")
    print(d[idx:idx+900] if idx >= 0 else "rowToModel 不存在")
    io.open(P, "w", encoding="utf-8").write(d)
    import sys; sys.exit(1)

# 3) addModel 加 weight（找 addModel 实现）
idx_add = d.find("fun addModel")
print("--- addModel 实现 ---")
print(d[idx_add:idx_add+500] if idx_add >= 0 else "addModel 不存在")
io.open(P, "w", encoding="utf-8").write(d)
print("Database.kt 已写入")