#!/bin/bash
# 綦桐AI网关 自动化冒烟测试（每次发版部署后跑：验证核心链路）
# 用法: bash scripts/smoke_test.sh [服务器IP] [web端口] [网关端口] [ssh私钥]
# 说明: /admin 页面从服务器内部抓取（公网可能限制），通过 ssh 执行
set -u
SRV="${1:-103.23.149.10}"
WEB="${2:-18080}"
GW="${3:-18889}"
KEY="${4:-/root/.ssh/hk_server_key}"
PASS=0; FAIL=0
ok(){ echo "  ✅ $1"; PASS=$((PASS+1)); }
bad(){ echo "  ❌ $1"; FAIL=$((FAIL+1)); }
SSH="ssh -i $KEY -o StrictHostKeyChecking=no -o ConnectTimeout=30"
# 服务器 sshd 间歇性握手限流（DEV_GUIDE 踩坑15）：ssh 执行带 3 次重试
ssh_retry(){ local i; for i in 1 2 3; do local R; R=$($SSH root@$SRV "$@" 2>/dev/null); if [ -n "$R" ]; then echo "$R"; return 0; fi; sleep 3; done; return 1; }

echo "===== 綦桐AI网关 冒烟测试 ====="
echo "目标: $SRV:$WEB (web) / $SRV:$GW (gateway)"
echo

# 1. 网关健康检查
echo "[1] 网关 /health"
H=$(ssh_retry "curl -s -m 15 http://127.0.0.1:$GW/health")
if echo "$H" | grep -q '"status":"ok"'; then
  V=$(echo "$H" | grep -o '"version":"[^"]*"' | cut -d'"' -f4)
  M=$(echo "$H" | grep -o '"models_count":[0-9]*' | cut -d: -f2)
  ok "健康检查通过（version=$V, models=$M）"
else
  bad "健康检查失败: $H"
fi

# 2. Web 首页可访问
echo "[2] Web 首页"
C=$(ssh_retry "curl -s -m 15 -o /dev/null -w '%{http_code}' http://127.0.0.1:$WEB/")
[ "$C" = "200" ] && ok "首页 HTTP $C" || bad "首页 HTTP $C"

# 3. 后台页含版本号（服务器内抓）
echo "[3] 后台页版本"
A=$(ssh_retry "curl -s -m 15 http://127.0.0.1:$WEB/admin")
echo "$A" | grep -q "APP_VER" && ok "后台 JS 正常" || bad "后台 JS 异常"

# 4. 网关鉴权开关（health 里 require_api_key 字段）
echo "[4] 网关鉴权"
REQ=$(echo "$H" | grep -o '"require_api_key":[a-z]*' | cut -d: -f2)
[ "$REQ" = "true" ] && ok "鉴权开启（require_api_key=true）" || bad "鉴权关闭？(require_api_key=$REQ)"

# 5. 关键页面进包验证（前端新增功能）
echo "[5] 前端功能进包"
for kw in "tryModel" "model-mappings" "pricing-rules" "channel-groups" "totp"; do
  N=$(echo "$A" | grep -c "$kw")
  [ "$N" -gt 0 ] && ok "包含 $kw ($N)" || bad "缺少 $kw"
done

echo
echo "===== 结果: ✅ $PASS 通过 / ❌ $FAIL 失败 ====="
[ "$FAIL" = "0" ] && echo "🎉 冒烟测试全部通过！" || echo "⚠️ 有失败项，请检查！"
exit $FAIL