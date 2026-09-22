#!/usr/bin/env bash
# 綦桐AI网关 Docker 一键启动/停止/日志脚本
set -e

CMD="docker compose"
if ! command -v docker >/dev/null; then echo "❌ 未安装Docker"; exit 1; fi
if ! docker compose version >/dev/null 2>&1; then CMD="docker-compose"; fi

case "${1:-up}" in
  up|start)
    echo "🚀 构建并启动綦桐AI网关..."
    $CMD up -d --build
    echo ""
    echo "✅ 启动完成！"
    echo "  🌐 Web后台:  http://服务器IP:18080   (默认 admin / admin888)"
    echo "  ⚡ 网关API:  http://服务器IP:18889/v1"
    ;;
  down|stop)
    echo "🛑 停止服务..."
    $CMD down
    ;;
  restart)
    echo "🔄 重启服务..."
    $CMD down && $CMD up -d --build
    ;;
  logs)
    $CMD logs -f --tail=200
    ;;
  status|ps)
    $CMD ps
    ;;
  *)
    echo "用法: $0 {up|down|restart|logs|status}"
    ;;
esac