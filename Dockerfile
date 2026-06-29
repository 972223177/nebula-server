# ============================================================
# Nebula Server Docker 镜像（运行阶段）
# ============================================================
# 构建策略：主机预编译 + Docker 仅负责运行
#   1. 主机执行:  ./gradlew :server:installDist
#   2. Docker 直接 COPY 编译产物，无需在容器内运行 Gradle
#
# 优势：
#   - 避免容器内 Maven Central TLS 握手问题
#   - 复用主机 Gradle 缓存，编译速度极快
#   - 镜像仅含 JRE + 分发包，体积更小
# ============================================================

FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制分发包（由主机 ./gradlew :server:installDist 生成）
# 产物路径: server/build/install/server/ 含 bin/ 启动脚本 + lib/ 全部 JAR
COPY server/build/install/server /app

# 复制 HOCON 配置文件
COPY config/ /app/config/

# gRPC 服务端口
EXPOSE 9090

# 启动脚本由 Gradle application 插件自动生成，CLASSPATH 已配好
ENTRYPOINT ["bin/server"]
