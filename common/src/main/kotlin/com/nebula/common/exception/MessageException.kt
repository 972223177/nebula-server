package com.nebula.common.exception

/**
 * 消息域业务异常（CQ-15/L19 — 已合并到 ChatException）。
 *
 * 保留为 typealias 过渡期，确保现有 `catch (e: MessageException)` 不破坏。
 * 新代码应直接使用 [ChatException]。
 */
typealias MessageException = ChatException
