#!/bin/bash
# ============================================================
# stop_debug.sh — 停止 Nebula Server 本地调试进程
# 用途：查找并停止通过 run_debug.sh 启动的 Gradle 服务进程
# ============================================================

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# 查找由当前项目启动的 Gradle 服务进程（:server:run）
PID=$(ps aux | grep -i gradle | grep "${PROJECT_DIR}" | grep -v grep | awk '{print $2}' || true)

if [ -z "$PID" ]; then
  echo "未找到正在运行的 Nebula Server 进程。"
  exit 0
fi

echo "正在停止 Nebula Server (PID: $PID) ..."
kill "$PID" 2>/dev/null || true

# 等待进程退出
for i in {1..5}; do
  if ps -p "$PID" > /dev/null 2>&1; then
    sleep 1
  else
    echo "Nebula Server 已停止。"
    exit 0
  fi
done

# 5秒后仍未退出，强制关闭
echo "进程未响应，强制关闭 ..."
kill -9 "$PID" 2>/dev/null || true
echo "Nebula Server 已强制停止。"

# 后备检测：验证端口 9090 是否已释放
PORT_PID=$(lsof -ti:9090 2>/dev/null || true)
if [ -n "$PORT_PID" ]; then
  echo "[后备检测] 端口 9090 仍被进程 ${PORT_PID} 占用，正在清理..."
  kill -9 "$PORT_PID" 2>/dev/null || true
  sleep 1
  # 确认端口已释放
  if lsof -ti:9090 > /dev/null 2>&1; then
    echo "[警告] 端口 9090 仍无法释放，请手动检查进程。"
  else
    echo "端口 9090 已释放。"
  fi
fi
