---
slug: fix-redis-testcontainers-build
description: 修复 service 模块 Testcontainers Docker 连接失败和 SeqService 序列号恢复 Bug
created: 2026-06-16
expert: debugger
mode: quick
tasks: 3
---

# Quick Plan: fix-redis-testcontainers-build

## 任务描述

修复以下问题：
1. `SeqServiceRedisRecoveryTest` 和 `RedisTestBaseTest` 因 Docker API 版本不兼容导致 Testcontainers 初始化失败
2. `SeqService.tryRestoreSeq` 使用 SETNX 存储 `nextSeq`，但 `nextSeq()` 方法使用 INCR（加 1 后返回），导致恢复后序列号偏移 +1

## 任务表

| # | 类型 | 文件 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | modify | service/build.gradle.kts | 添加 systemProperty("api.version", "1.44") | 编译通过 |
| 2 | modify | service/.../SeqService.kt | tryRestoreSeq 存储 nextSeq - 1 补偿 INCR | 测试通过 |
| 3 | verify | service/.../RedisTestBaseTest | 确认同一 Docker 配置修复覆盖该测试 | 测试通过 |

## 上下文引用

- 项目: .planning/PROJECT.md
