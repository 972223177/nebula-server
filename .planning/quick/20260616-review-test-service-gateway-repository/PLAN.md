---
slug: review-test-service-gateway-repository
description: 审查 service、gateway、repository 模块测试与测试目标的一致性
created: 2026-06-16
expert: code-reviewer
mode: quick
tasks: 3
---

# Quick Plan: review-test-service-gateway-repository

## 任务描述

审查 nebula_server 项目中 service、gateway、repository 三个模块下的所有测试文件，评估它们是否符合对应实现代码的测试目标：

- **service 模块**：9 个测试文件（用户/隐私/聊天/好友/序列/Redis/死信/会话）
- **gateway 模块**：57 个测试文件（编解码/推送/分发器/Handler/拦截器/会话/DI/重连）
- **repository 模块**：6 个测试文件（Redis/Repository 集成/Flyway/会话）

审查要点：
1. 测试是否覆盖了实现类的主要业务场景和边界条件
2. 测试是否存在遗漏（重要路径未测、异常分支未覆盖）
3. 测试本身的质量问题（重复代码、mock 过度、断言不足、不可靠测试）
4. 与 PROJECT.md 中要求的特性、行为是否一致

## 任务表

| # | 类型 | 范围 | 操作 | 验证 |
|---|------|------|------|------|
| 1 | review | service 模块测试 | 审查 service/src/test 下所有测试文件 | 输出审查结果 |
| 2 | review | gateway 模块测试 | 审查 gateway/src/test 下所有测试文件 | 输出审查结果 |
| 3 | review | repository 模块测试 | 审查 repository/src/test 下所有测试文件 | 输出审查结果 |

## 上下文引用

- 项目: .planning/PROJECT.md
- service 测试文件: 9 个
- gateway 测试文件: 57 个
- repository 测试文件: 6 个
