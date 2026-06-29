#!/usr/bin/env bash
# ============================================================
# Nebula Server — 本地 Docker 完整打包部署脚本
# ============================================================
# 构建流程：主机 Gradle 编译 → Docker COPY 产物 → docker compose up
# Gradle 在主机运行，复用本地缓存，避免容器内 Maven Central TLS 问题
#
# 用法:
#   ./docker_deploy.sh              # 增量编译 + 构建镜像 + 启动
#   ./docker_deploy.sh --rebuild    # 强制全量编译 + 无缓存重建镜像
#   ./docker_deploy.sh --logs       # 实时查看 server 日志
#   ./docker_deploy.sh --stop       # 停止所有服务
#   ./docker_deploy.sh --clean      # 停止 + 删除容器/镜像/卷
#   ./docker_deploy.sh --db-logs    # 只查看 DB + Redis 相关日志
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
    --db-logs|-d)
        echo "==> 实时查看 DB + Redis 日志..."
        docker compose -f "$COMPOSE_FILE" logs -f server \
            | grep -iE "repository\.redis|repository\.dao|hikari|hibernate|lettuce|flyway|jdbc"
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
        echo "==> 强制全量编译..."
        ./gradlew :server:installDist --no-daemon
        echo ""
        echo "==> 无缓存重建镜像..."
        docker compose -f "$COMPOSE_FILE" build --no-cache server
        docker compose -f "$COMPOSE_FILE" up -d
        echo ""
        echo "==> 等待服务就绪（Koin DI + Flyway 迁移 + 序列号恢复）..."
        sleep 6
        echo "==> 最近日志:"
        docker compose -f "$COMPOSE_FILE" logs --tail=20 server
        exit 0
        ;;
    --help|-h)
        echo "用法: $0 [--rebuild|--logs|--db-logs|--stop|--clean]"
        exit 0
        ;;
esac

# -- 默认：增量编译 + 构建镜像 + 启动 ---------------------------
echo "==> 主机编译（增量，复用本地 Gradle 缓存）..."
./gradlew :server:installDist --no-daemon

echo ""
echo "==> 构建 Docker 镜像（COPY 编译产物）..."
docker compose -f "$COMPOSE_FILE" build server

echo "==> 启动服务（MySQL + Redis + Nebula Server）..."
docker compose -f "$COMPOSE_FILE" up -d

echo ""
echo "==> 等待服务就绪（Koin DI + Flyway 迁移 + 序列号恢复）..."
sleep 6

echo "==> 最近日志:"
docker compose -f "$COMPOSE_FILE" logs --tail=20 server

echo ""
echo "✅ 部署完成"
echo "   查看日志:    ./docker_deploy.sh --logs"
echo "   DB/Redis日志: ./docker_deploy.sh --db-logs"
echo "   停止服务:    ./docker_deploy.sh --stop"
echo "   完全清理:    ./docker_deploy.sh --clean"
