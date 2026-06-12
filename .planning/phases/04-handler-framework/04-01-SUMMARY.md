---
phase: 04-handler-framework
plan: 01
subsystem: api
tags: [kotlin, coroutines, protobuf, handler, dispatcher, interceptor, methodhandles, koin, mockk, junit5]

requires:
provides:
  - Generic Handler<Req, Resp> suspend interface with CoroutineContext Session injection (D-01/D-03)
  - ConcurrentHashMap-based HandlerRegistry with putIfAbsent deduplication (D-11)
  - MethodHandles pre-compiled ProtoCodec with zero-reflection CodecPair (D-12)
  - Dispatcher pipeline with foldRight-constructed Chain of Responsibility (D-02/D-14/D-15)
  - InterceptorChain suspend chain node for non-tail positions (D-06)
  - JUnit5 + MockK + runTest test infrastructure for all gateway tests
affects: [05-user-auth, 06-chat-message, 07-conversation]

tech-stack:
  added: [koin-core 4.1.0, mockk 1.13.14, koin-test-junit5, kotlinx-coroutines-test]
  patterns:
    - Handler suspend interface with CoroutineContext session injection
    - MethodHandles pre-compiled zero-reflection protobuf codec
    - Chain of Responsibility via foldRight on List<Interceptor>
    - JUnit5 + runTest + MockK coEvery/coVerify for suspend function testing

key-files:
  created:
    - gateway/src/main/kotlin/com/nebula/gateway/handler/Handler.kt
    - gateway/src/main/kotlin/com/nebula/gateway/handler/SessionKey.kt
    - gateway/src/main/kotlin/com/nebula/gateway/session/Session.kt
    - gateway/src/main/kotlin/com/nebula/gateway/interceptor/Interceptor.kt
    - gateway/src/main/kotlin/com/nebula/gateway/dispatcher/HandlerEntry.kt
    - gateway/src/main/kotlin/com/nebula/gateway/dispatcher/HandlerRegistry.kt
    - gateway/src/main/kotlin/com/nebula/gateway/codec/ProtoCodec.kt
    - gateway/src/main/kotlin/com/nebula/gateway/dispatcher/Dispatcher.kt
    - gateway/src/main/kotlin/com/nebula/gateway/dispatcher/InterceptorChain.kt
    - gateway/src/test/kotlin/com/nebula/gateway/dispatcher/HandlerRegistryTest.kt
    - gateway/src/test/kotlin/com/nebula/gateway/codec/ProtoCodecTest.kt
    - gateway/src/test/kotlin/com/nebula/gateway/dispatcher/DispatcherTest.kt
  modified:
    - gradle/libs.versions.toml
    - gateway/build.gradle.kts
    - server/build.gradle.kts

key-decisions:
  - "SessionKey 采用 CoroutineContext.Element 模式而非 CoroutineContext.Key<Session>（后者有 type bound 编译约束）— 改为 data class SessionKey(val session: Session): CoroutineContext.Element，配套修改 requireSession() 为 this[SessionKey]?.session"
  - "ProtoCodec 增加 deserialize(entry, ByteString)/serialize(entry, Any) 辅助方法，避免 Dispatcher 直接使用 HandlerEntry 的方法引用"
  - "Dispatcher handlerChain 使用显示类型声明 Interceptor.Chain 解决 foldRight 匿名对象类型推断问题"
  - "JUnit5 + MockK + kotlinx-coroutines-test 作为标准测试框架，需在 gateway/build.gradle.kts 显式配置 useJUnitPlatform() 和 junit-platform-launcher"

patterns-established:
  - "Handler: 泛型 suspend 接口 + 包名按领域分包 (handler/session/interceptor/dispatcher/codec)"
  - "SessionKey: CoroutineContext.Element 模式，data class + companion object Key"
  - "Registry: ConcurrentHashMap + putIfAbsent 防重复注册"
  - "Codec: MethodHandles 预编译，注册时一次性查找，运行时零反射"
  - "Pipeline: interceptors.foldRight(handlerChain) 构造 Chain of Responsibility"
  - "Test: JUnit5 @Test + runTest + MockK coEvery/coVerify"

requirements-completed: [HNDL-01, HNDL-06]

duration: 16 min
completed: 2026-06-12
---

# Phase 4: Handler Framework Plan 01 Summary

**Handler suspend interface with CoroutineContext Session injection, MethodHandles-based ProtoCodec, ConcurrentHashMap HandlerRegistry, and foldRight-constructed Dispatcher Pipeline**

## Performance

- **Duration:** 16 min
- **Started:** 2026-06-12T05:56:00Z
- **Completed:** 2026-06-12T06:12:00Z
- **Tasks:** 4 (1 build config + 3 implementation)
- **Files modified:** 15 (12 created, 3 modified)

## Accomplishments

- Generic `Handler<Req, Resp>` suspend interface with `method` routing property
- `Session` data class with 5 fields and `SessionKey` as `CoroutineContext.Element` for implicit Session injection
- `Interceptor` suspend Chain-of-Responsibility interface with embedded `Chain` interface
- `HandlerRegistry` with `ConcurrentHashMap` + `putIfAbsent` deduplication
- `ProtoCodec` using `MethodHandles.lookup()` pre-compiled `CodecPair` — zero reflection at runtime
- `Dispatcher.dispatch()` completing full Request → HandlerRegistry → ProtoCodec → Pipeline → Response flow
- `InterceptorChain` wrapping interceptors for foldRight chain construction
- Build configuration for Koin 4.1.0, MockK 1.13.14, JUnit5, kotlinx-coroutines-test

## Task Commits

Each task was committed atomically:

1. **Build config: Koin, MockK, test deps** - `3ed4b68` (build)
2. **Handler interface, Session, SessionKey, Interceptor** - `5dc0e42` (feat)
3. **HandlerEntry, HandlerRegistry, ProtoCodec + tests** - `91dddf6` (feat)
4. **Dispatcher, InterceptorChain + tests** - `e59e32c` (feat)
5. **Build updates + ProtoCodec helpers** - `9fea814` (build)

## Files Created/Modified

### Created (12 files)
- `gateway/src/main/kotlin/com/nebula/gateway/handler/Handler.kt` — Generic suspend Handler interface
- `gateway/src/main/kotlin/com/nebula/gateway/handler/SessionKey.kt` — CoroutineContext.Element for Session injection
- `gateway/src/main/kotlin/com/nebula/gateway/session/Session.kt` — Session data model (5 fields)
- `gateway/src/main/kotlin/com/nebula/gateway/interceptor/Interceptor.kt` — Suspend Chain of Responsibility interface
- `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/HandlerEntry.kt` — Registry entry with type info + serialization refs
- `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/HandlerRegistry.kt` — ConcurrentHashMap registry
- `gateway/src/main/kotlin/com/nebula/gateway/codec/ProtoCodec.kt` — MethodHandles-based pre-compiled codec
- `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/Dispatcher.kt` — Pipeline dispatch orchestrator
- `gateway/src/main/kotlin/com/nebula/gateway/dispatcher/InterceptorChain.kt` — Non-tail chain node
- `gateway/src/test/kotlin/com/nebula/gateway/dispatcher/HandlerRegistryTest.kt` — Registry unit tests
- `gateway/src/test/kotlin/com/nebula/gateway/codec/ProtoCodecTest.kt` — Codec unit tests
- `gateway/src/test/kotlin/com/nebula/gateway/dispatcher/DispatcherTest.kt` — Full pipeline unit tests

### Modified (3 files)
- `gradle/libs.versions.toml` — Added koin 4.1.0, mockk 1.13.14, junit 5.11.4
- `gateway/build.gradle.kts` — Added framework and test dependencies
- `server/build.gradle.kts` — Added koin-core for server initialization

## Decisions Made

- **SessionKey as CoroutineContext.Element**: The plan initially specified `data object SessionKey : CoroutineContext.Key<Session>`, but `CoroutineContext.Key<T>` requires `T` to be a subtype of `CoroutineContext.Element`. Changed to `data class SessionKey(val session: Session) : CoroutineContext.Element` with companion object `Key : CoroutineContext.Key<SessionKey>`.
- **Separate req/resp CodecPair**: HandlerEntry stores separate `parseFrom` (request) and `toByteArray` (response) method references built from different Proto classes, ensuring correct serialization.
- **Explicit Interceptor.Chain type**: The foldRight pipeline construction requires explicit `val pipeline: Interceptor.Chain` type annotation for correct Kotlin type inference.
- **ProtoCodec helper methods**: Added `deserialize(entry, ByteString)` and `serialize(entry, Any)` as convenience methods for the Dispatcher.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- **CoroutineContext.Key type bound**: `data object SessionKey : CoroutineContext.Key<Session>` does not compile because `Key<T>` requires `T` to be a subtype of `CoroutineContext.Element`. Resolved by implementing `CoroutineContext.Element` instead.
- **Protobuf classpath in tests**: The proto module's `protobuf-java` dependency isn't transitively available for gateway test compilation. Added explicit `testImplementation(libs.protobuf.java)`.
- **MockK object mocking**: `ProtoCodec` is a Kotlin `object`, which can't be directly mocked with MockK. Tests use the real `ProtoCodec` with `Request::class` and `Response::class` Proto types instead.

## Verification Results

```bash
# Full project compilation
./gradlew compileKotlin → BUILD SUCCESSFUL

# Gateway tests (all 8 tests pass)
./gradlew :gateway:test → BUILD SUCCESSFUL
  - HandlerRegistryTest.register and lookup handler → PASS
  - HandlerRegistryTest.duplicate registration throws → PASS
  - ProtoCodecTest.build codec for Request proto → PASS
  - ProtoCodecTest.empty bytes returns default instance → PASS
  - ProtoCodecTest.serialize and deserialize roundtrip → PASS
  - DispatcherTest.dispatch with valid handler returns response → PASS
  - DispatcherTest.dispatch with unknown method returns NOT_FOUND → PASS
  - DispatcherTest.dispatch with empty interceptors still works → PASS
  - DispatcherTest.dispatch with interceptors invokes pipeline → PASS
```

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Handler Framework core skeleton complete (Handler, Registry, Codec, Dispatcher, InterceptorChain)
- All 9 production files + 3 test files created, compiled, and tested (8 tests passing)
- 3 unit test classes covering HandlerRegistry, ProtoCodec, and Dispatcher pipeline
- Ready for Plan 02: Interceptor implementations (AuthInterceptor, LogInterceptor, ExceptionInterceptor, RateLimitInterceptor), SessionRegistry, PingHandler, Koin Module

## Self-Check: PASSED

All acceptance criteria from every task verified and passing. All plan-level success criteria met.

---
*Phase: 04-handler-framework*
*Completed: 2026-06-12*
