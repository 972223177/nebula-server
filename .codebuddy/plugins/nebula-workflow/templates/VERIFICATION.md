---
phase: N
verifier: nx-verifier
status: passed|failed|partial
---
# Phase N 验证报告

## L1 存在性（文件检测）
| 文件 | 预期路径 | 实际路径 | 状态 |
|------|---------|---------|------|
| <文件名> | <路径> | <路径> | ✅/❌ |

## L2 内容实在性（存根检测）
| 文件 | 行数 | 存根数 | 存根详情 | 状态 |
|------|------|--------|---------|------|
| <文件名> | N | 0 | — | ✅ |

## L3 连接性（连线检测）
| 连线 | 预期 | 实际 | 状态 |
|------|------|------|------|
| Handler → Service | 注入 + 调用 | <证据> | ✅ |
| Service → Repository | 注入 + 调用 | <证据> | ✅ |

## L4 数据流通（端到端链路）
| 数据路径 | 链路完整 | 状态 |
|-----------|---------|------|
| gRPC → Handler → Service → Repository → DB → Response | ✅ | ✅ |

## 测试结果
```
./gradlew :module:test
> X tests completed, Y passed, Z failed
```

## 最终裁决
- [ ] PASSED —— 四层验证全部通过
- [ ] PARTIAL —— 部分层级有 gap
- [ ] FAILED —— 关键层级未通过
