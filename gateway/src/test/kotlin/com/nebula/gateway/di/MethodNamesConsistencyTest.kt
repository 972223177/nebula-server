package com.nebula.gateway.di

import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.MethodNames
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MethodNames 一致性守护测试（方案 A）。
 *
 * 以 [MethodNames] 为唯一基准，校验双向一致性：
 * 1. 每个 Handler 的 `method` 都必须来自 MethodNames 常量（杜绝 Handler 内硬编码路由字符串）；
 * 2. 每个非 PREFIX 的 MethodNames 常量都必须被某个 Handler 注册（杜绝孤儿常量 / 漏注册 method）；
 * 3. Handler 之间不存在重复的 method 路由（重复会被 HandlerRegistry 拒绝注册，此处兜底）。
 *
 * MethodNames 常量通过 Java 反射枚举（const val 编译为嵌套 object 类的 static final 字段），
 * 不引入 kotlin-reflect 依赖。新增 Handler 时若忘记在 MethodNames 登记、或 MethodNames 出现
 * 未使用的常量，本测试即失败，闭合「加 method 改多处」中的漂移缺口。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MethodNamesConsistencyTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun allHandlerMethodsResolveToMethodNamesConstants() {
        startKoin {
            modules(
                frameworkModule,
                GatewayTestModules.handlerModule(),
                GatewayTestModules.externalModule()
            )
        }
        val handlers = GlobalContext.get().getAll<Handler<*, *>>()
        val handlerMethods = handlers.map { it.method }.toSet()

        // 枚举 MethodNames 中全部 String 常量（method + *_PREFIX），通过 Java 反射读取嵌套 object 的静态字段
        val constants = MethodNames::class.java.declaredClasses.flatMap { nested ->
            nested.fields
                .filter { field ->
                    field.type == String::class.java &&
                    java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    java.lang.reflect.Modifier.isFinal(field.modifiers)
                }
                .map { field -> field.name to (field.get(null) as String) }
        }
        val methodConstants = constants
            .filter { (name, _) -> !name.endsWith("_PREFIX") }
            .map { it.second }
            .toSet()

        // (1) 每个 handler.method 必须命中某个 method 常量
        handlerMethods.forEach { m ->
            assertTrue(
                m in methodConstants,
                "Handler method '$m' 未引用 MethodNames 常量（疑似硬编码路由字符串，应改为 MethodNames.X）"
            )
        }

        // (2) 每个 method 常量必须被某 handler 注册（无孤儿常量 / 漏注册）
        methodConstants.forEach { c ->
            assertTrue(
                c in handlerMethods,
                "MethodNames 常量 '$c' 未被任何 Handler 注册（孤儿常量或漏注册 Handler）"
            )
        }

        // (3) method 路由无重复
        assertEquals(
            handlerMethods.size,
            handlers.size,
            "存在重复的 method 路由（HandlerRegistry 将拒绝注册）"
        )
    }
}
