---
title: "项目代码警告评估"
description: "评估项目所有代码警告，按'可安全修改'和'需谨慎修改'分类"
status: "in_progress"
started: "2026-06-12T13:52:00Z"
---

## 目标

全面扫描 nebula-server 项目中 Gradle 构建、Kotlin 编译器、IDE 层面的所有警告（warning）和潜在问题，评估每个问题的修改风险，并给出修改建议。

## 方法

1. 运行 `./gradlew clean classes --warning-mode all` 获取 Gradle 构建警告
2. 运行 `./gradlew clean compileKotlin` 获取 Kotlin 编译期警告
3. 手动审查源代码中的 IDE 级别警告（未使用导入、变量、未捕获类型擦除等）
4. 分类评估每个警告的可修改性

## 发现分类

### 分类 A：可以按警告提示直接更改（低风险）
### 分类 B：需要谨慎修改（中等风险）
### 分类 C：代码 Bug（非警告，但需要修复）
