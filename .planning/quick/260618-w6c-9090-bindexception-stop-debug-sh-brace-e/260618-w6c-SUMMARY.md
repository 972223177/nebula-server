---
phase: quick
plan: 260618-w6c
slug: 260618-w6c-9090-bindexception-stop-debug-sh-brace-e
description: 修复端口 9090 BindException — stop_debug.sh 新增 lsof 端口占用后备检测
subsystem: infra
tags:
  - infra
  - scripts
  - debug
  - port-conflict
requires: []
affects:
  - stop_debug.sh
  - run_debug.sh (indirect)
tech-stack:
  scripts:
    - bash
    - lsof
key-files:
  created:
    - stop_debug.sh (was untracked, now committed)
  modified: []
decisions: []
metrics:
  duration: ~2 min
  files_changed: 1
  commits: 1
---

# Quick Plan: 9090 BindException — stop_debug.sh 端口占用后备检测

## 执行摘要

在 `stop_debug.sh` 现有的 Gradle 进程 kill + 等待循环逻辑之后，新增 `lsof -ti:9090` 后备检测。若 Gradle 进程虽已终止但 JVM 子进程仍持有端口 9090，脚本现在会强制清理该占用进程并输出明确日志，避免下次 `run_debug.sh` 报 `BindException: Address already in use`。

修改仅涉及 1 个文件，新增 13 行（第 37-49 行）。

## 任务执行

| # | 类型 | 文件 | 操作 |
|---|------|------|------|
| 1 | modify | stop_debug.sh | 在 Gradle 进程 kill + 强制关闭逻辑之后，增加 `lsof -ti:9090` 后备检测 |

## 验证结果

| 检查项 | 结果 |
|--------|------|
| `bash -n stop_debug.sh` 语法检查 | PASSED |
| `which lsof` 可用性 | PASSED (at /usr/sbin/lsof) |
| Brace expansion 语法 | PASSED (`{1..5}` 展开正常) |

## Deviations from Plan

无 — 计划按原样执行。

## Commits

| Hash | Message |
|------|---------|
| a4030bb | `fix(quick-260618-w6c): stop_debug.sh 增加 lsof 端口 9090 占用后备检测` |

## Self-Check: PASSED

- `stop_debug.sh` 已创建并包含新增的后备检测逻辑
- Commit `a4030bb` 创建成功
- 语法验证通过
