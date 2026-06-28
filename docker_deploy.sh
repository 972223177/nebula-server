#!/usr/bin/env bash
# ============================================================
# Nebula Server — 本地 Docker 完整打包部署脚本
# ============================================================
# 用法:
#   ./docker_deploy.sh              # 构建 + 启动（后台）
#   ./docker_deploy.sh --rebuild    # 强制重建镜像
#   ./docker_deploy.sh --logs       # 查看日志
#   ./docker_deploy.sh --stop       # 停止服务
#   ./docker_deploy.sh --clean      # 停止 + 删除容器/镜像/卷
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE_FILE="docker-compose.yml"

# -- 命令分发 --------------------------------------------------
case "${1:-}" in
    --logs|-l)
        docker compose -f "$COMPOSE_FILE" logs -f server
        exit 0
        ;;
    --stop|-s)
        echo "==> 停止所有服务..."
        docker compose -f "$COMPOSE_FILE" down
        exit 0
        ;;
    --clean|-c)
        echo "==> 停止并清理容器/镜像/卷..."
        docker compose -f "$COMPOSE_FILE" down -v --rmi local
        exit 0
        ;;
    --rebuild|-r)
        echo "==> 强制重建镜像 + 启动（无缓存）..."
        docker compose -f "$COMPOSE_FILE" build --no-cache server
        docker compose -f "$COMPOSE_FILE" up -d
        echo ""
        echo "✅ 部署完成，等待服务就绪..."
        sleep 5
        docker compose -f "$COMPOSE_FILE" logs --tail=20 server
        exit 0
        ;;
    --help|-h)
        echo "用法: $0 [--rebuild|--logs|--stop|--clean]"
        exit 0
        ;;
esac

# -- 默认：增量构建 + 启动 ---------------------------------------
echo "==> 构建镜像（增量，利用 Docker 缓存）..."
docker compose -f "$COMPOSE_FILE" build server

echo "==> 启动服务（MySQL + Redis + Nebula Server）..."
docker compose -f "$COMPOSE_FILE" up -d

echo ""
echo "==> 等待服务就绪（约 5s 加载 Koin DI + Flyway 迁移 + 序列号恢复）..."
sleep 6

echo "==> 最近日志:"
docker compose -f "$COMPOSE_FILE" logs --tail=20 server

echo ""
echo "✅ 部署完成"
echo "   查看日志:  ./docker_deploy.sh --logs"
echo "   停止服务:  ./docker_deploy.sh --stop"
echo "   完全清理:  ./docker_deploy.sh --clean"
