#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d "venv" ]; then
    echo "错误: 未找到虚拟环境，请先运行 ./setup.sh"
    exit 1
fi

# 解析 site-packages 实际路径（python3.* 通配符）
SITE_PACKAGES=$(echo venv/lib/python3.*/site-packages)
export PYTHONPATH="$PWD/gen/python:$PWD/$SITE_PACKAGES"

exec ./venv/bin/python im_auto_test.py "$@"
