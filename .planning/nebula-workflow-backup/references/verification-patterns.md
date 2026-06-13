# 验证模式

Nebula 的四层验证模型（存在性→内容实在性→连接性→数据流通）的具体检测模式。
参考 GSD 的 verification-patterns.md 提供的可执行命令。

## 核心原则

**存在 ≠ 实现**。文件存在不代表功能可用。验证必须四层递进。

## L1 存在性 — 文件存在检测

```bash
# 检查文件是否在预期路径
check_exists() {
  local file="$1"
  if [ -f "$file" ]; then
    echo "EXISTS: $file"
    return 0
  else
    echo "MISSING: $file ❌"
    return 1
  fi
}
```

## L2 内容实在性 — 存根检测

### 通用存根模式

```bash
# 检查注释存根
grep -n -E "TODO|FIXME|XXX|HACK|PLACEHOLDER" "$file"
grep -n -E "待实现|占位|后续补充|临时方案" "$file"  # 中文存根

# 检查空实现
grep -n -E "return null|return Unit|return \{\}|throw NotImplementedError" "$file"
grep -n -E "TODO\(\)|error\(.*not implemented" "$file"

# 检查仅日志输出
grep -n -E "logger\.(info|debug|trace)\(.*\)" "$file" | \
  grep -v "logger\.(error|warn)"  # 排除错误日志
```

### Kotlin Handler 存根检测

```kotlin
// 以下模式表示存根：
override suspend fun handleMethod(request: Request): Response {
    return Response.getDefaultInstance()  // ❌ 空响应
}

override suspend fun handleMethod(request: Request): Response {
    TODO("待实现")  // ❌ TODO 占位
}
```

```bash
# Handler 必须有 Service 调用
grep -n -E "service\.|repository\.|manager\." <handler_file>

# Handler 方法数 > 0
grep -c "override suspend fun" <handler_file>
```

### Kotlin Service 存根检测

```kotlin
// 以下模式表示存根：
class MyServiceImpl : MyService {
    override suspend fun process(): Result {
        return Result.EMPTY  // ❌ 空结果
    }
}
```

```bash
# Service 必须有 Repository 调用
grep -n -E "repository\.|dao\." <service_file>

# Service 方法体长度 > 3 行
awk '/override suspend fun/,/^    \}/' <service_file> | wc -l
```

### Kotlin Repository 存根检测

```bash
# Repository 必须有查询定义
grep -n -E "@Query|fun findBy|fun findAll|fun save|fun delete" <repository_file>

# NULL 返回值检测（应该有实际数据）
grep -n -E "return null" <repository_file>
```

## L3 连接性 — 连线检测

### Handler → Service 连线

```bash
# Handler 是否注入 Service
grep -rn "private.*Service" <handler_dir>

# 注入的 Service 是否被实际调用
grep -rn "service\.|\.(send|pull|read|create|update|delete)" <handler_dir>
```

### Service → Repository 连线

```bash
# Service 是否注入 Repository
grep -rn "private.*Repository" <service_dir>

# Repository 方法是否被调用
grep -rn "repository\.|\.(findBy|findAll|save|delete)" <service_dir>
```

### DI 模块连线

```bash
# Koin 模块是否注册所有组件
grep -rn "single\s*\{|factory\s*\{" <module_dir>

# 检查是否有 Service/Repository 未被注册
for SERVICE in (所有 Service 类); do
  grep -q "$SERVICE" <module_dir> || echo "MISSING from DI: $SERVICE"
done
```

### gRPC 注册连线

```bash
# gRPC Service 实现是否注册
grep -rn "addService\|bindService" <server_config>

# Handler 是否实现正确的 ServiceBase
grep -rn "ServiceImplBase\|CoroutineImplBase" <handler_dir>
```

## L4 数据流通 — 端到端链路追踪

### 完整数据路径

```
gRPC Request → Handler.deserialize() → Service.process() → Repository.query() → DB → Result → Response
```

### 逐节点检查

```bash
# 节点 1: Handler 是否转换 Protobuf → Domain
grep -rn "\.toDomain\|\.toEntity\|\.toModel" <handler_dir>

# 节点 2: Service 是否调用 Repository
grep -rn "repository\." <service_dir>

# 节点 3: Repository 是否定义 SQL 查询
grep -rn "@Query|fun find" <repository_dir>

# 节点 4: 结果是否正确序列化回 Protobuf
grep -rn "\.toProto\|\.toResponse" <handler_dir>
```

## 测试验证

```bash
# 运行模块测试
./gradlew :module:test

# 统计测试通过率
./gradlew :module:test 2>&1 | grep -E "tests? (completed|passed|failed)"

# 检查特定测试类
./gradlew :module:test --tests "*<TestClass>"
```

## 自动化验证脚本

```bash
#!/bin/bash
# 四层验证执行脚本

PHASE_DIR="$1"

check_exists() { [ -f "$1" ] && echo "L1 PASS: $1" || echo "L1 FAIL: $1"; }
check_substantive() {
  local file="$1"
  local stubs=$(grep -c -E "TODO|FIXME|PLACEHOLDER|待实现" "$file" 2>/dev/null || echo 0)
  [ "$stubs" -eq 0 ] && echo "L2 PASS: $file" || echo "L2 FAIL: $file ($stubs stubs)"
}
check_wired() {
  local component="$1" dependency="$2"
  grep -q "$dependency" "$component" && echo "L3 PASS: $component → $dependency" || echo "L3 FAIL: $component → $dependency"
}

# 从 PLAN.md 提取预期文件列表
EXPECTED_FILES=($(grep -oP 'src/\S+\.kt' "${PHASE_DIR}/*-PLAN.md"))

for file in "${EXPECTED_FILES[@]}"; do
  check_exists "$file"
  check_substantive "$file"
done
```
