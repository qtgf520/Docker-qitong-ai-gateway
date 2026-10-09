#!/usr/bin/env bash
# 綦桐AI网关 xp 服务器一键启动/停止/日志
# docker 在 /xp/server/docker，compose 用完整路径
set -e
export PATH=/xp/server/docker:$PATH

case "${1:-up}" in
  up|start)
    echo "🚀 构建并启动綦桐AI网关(xp)..."
    docker compose up -d --build
    echo "✅ 启动完成！"
    echo "  🌐 Web后台:  http://192.168.2.5:18080"
    echo "  ⚡ 网关API:  http://192.168.2.5:18889/v1"
    ;;
  down|stop)
    docker compose down
    ;;
  restart)
    docker compose down && docker compose up -d --build
    ;;
  logs)
    docker compose logs -f --tail=200
    ;;
  status)
    docker compose ps
    ;;
  *)
    echo "用法: $0 {up|down|restart|logs|status}"
    ;;
esac