# ============================================================
# 阶段一：构建 — 编译 Kotlin 多模块并生成可分发的安装目录
# ============================================================
FROM eclipse-temurin:21-jdk AS build

# 修复 Docker 容器内 JDK 访问 Maven Central 的 TLS 握手问题
ENV GRADLE_OPTS="-Dhttps.protocols=TLSv1.2,TLSv1.3"

WORKDIR /project

# 先复制 Gradle 配置，利用 Docker 缓存加速依赖下载
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# 移除 macOS 主机上的 JDK 路径（容器内使用基础镜像自带的 JDK）
RUN sed -i '' '/org\.gradle\.java\.home/d' gradle.properties 2>/dev/null || \
    sed -i '/org\.gradle\.java\.home/d' gradle.properties

# 下载依赖（无源码时可缓存此层）
RUN ./gradlew dependencies --no-daemon -q || true

# 复制源码
COPY . .

# 再次移除 macOS JDK 路径（COPY . . 覆盖了之前的修改）
RUN sed -i '' '/org\.gradle\.java\.home/d' gradle.properties 2>/dev/null || \
    sed -i '/org\.gradle\.java\.home/d' gradle.properties

# 构建 server 模块的分发包（产物：server/build/install/server/）
RUN ./gradlew :server:installDist --no-daemon

# ============================================================
# 阶段二：运行 — 仅复制 JRE + 分发包 + 配置，最小化镜像
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制分发包（含 bin/ 启动脚本和 lib/ 全部 JAR）
COPY --from=build /project/server/build/install/server /app

# 复制 HOCON 配置文件（application.conf + dev.conf）
COPY --from=build /project/config/ /app/config/

# gRPC 服务端口
EXPOSE 9090

# 启动脚本由 application 插件生成，包含完整的 CLASSPATH
ENTRYPOINT ["bin/server"]
