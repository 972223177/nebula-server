#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "===== 创建 Python 虚拟环境 ====="
python3 -m venv venv || python -m venv venv

echo "===== 升级 pip ====="
./venv/bin/pip install --upgrade pip -q

echo "===== 安装依赖 ====="
./venv/bin/pip install -r requirements.txt -q

echo "===== 编译 Proto ====="
SITE_PACKAGES=$(echo venv/lib/python3.*/site-packages)
export PYTHONPATH="$PWD/gen/python:$PWD/$SITE_PACKAGES"
./venv/bin/python im_auto_test.py --build-proto

echo ""
echo "===== 初始化完成 ====="
echo ""
echo "运行方式:"
echo "  ./run.sh --accounts-file accounts.json"
echo "  ./run.sh --user alice:pass123 --user bob:pass456"
echo "  ./run.sh --mode register --count 2"
