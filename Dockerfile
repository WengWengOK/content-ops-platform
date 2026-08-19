# ── Content Ops 服务镜像（多阶段构建，支持三服务复用同一 Dockerfile）──
# 构建上下文必须是仓库根目录（访问 content-ops-configs 父 POM 与 5 个子模块）。
#
# 参数：
#   MAVEN_MODULE  : 要编译的子模块 artifactId（content-ops-server / content-ops-orchestrator /
#                   content-ops-worker / content-ops-tools），默认 content-ops-server（Legacy 单体）
#
# 使用示例：
#   docker build -t contentops/server .                                                          # Legacy 单体
#   docker build --build-arg MAVEN_MODULE=content-ops-orchestrator -t contentops/orchestrator .  # Phase3 orchestrator
#   docker build --build-arg MAVEN_MODULE=content-ops-worker       -t contentops/worker .        # Phase3 worker
#   docker build --build-arg MAVEN_MODULE=content-ops-tools        -t contentops/tools .         # Phase3 tools
# ──────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MODULE=content-ops-server

WORKDIR /workspace

# 按 reactor 顺序 COPY 全部模块（保证 Maven 能解析 module 间依赖）
COPY pom.xml ./pom.xml
COPY content-ops-configs ./content-ops-configs
COPY content-ops-common ./content-ops-common
COPY content-ops-server ./content-ops-server
COPY content-ops-orchestrator ./content-ops-orchestrator
COPY content-ops-worker ./content-ops-worker
COPY content-ops-tools ./content-ops-tools

# 1) Reactor 构建：只编译指定模块 + 其依赖（-am），跳过测试
RUN mvn -B -f content-ops-configs/pom.xml \
        -pl :${MAVEN_MODULE} -am \
        package -DskipTests

# 2) jar 路径归一化：legacy-server 是双层目录（/workspace/content-ops-server/content-ops-server/target），
#    其他是单层目录（/workspace/{module}/target），jar 还可能带/不带版本号，统一拷到 /workspace/app.jar
RUN set -e; \
    CANDIDATES=" \
      /workspace/${MAVEN_MODULE}/target/${MAVEN_MODULE}.jar \
      /workspace/${MAVEN_MODULE}/target/${MAVEN_MODULE}-1.0.0.jar \
      /workspace/${MAVEN_MODULE}/target/${MAVEN_MODULE}-1.0.0-exec.jar \
      /workspace/content-ops-server/${MAVEN_MODULE}/target/${MAVEN_MODULE}.jar \
      /workspace/content-ops-server/${MAVEN_MODULE}/target/${MAVEN_MODULE}-1.0.0-exec.jar \
      /workspace/content-ops-server/${MAVEN_MODULE}/target/${MAVEN_MODULE}-1.0.0.jar \
    "; \
    FOUND=""; \
    for P in $CANDIDATES; do \
      if [ -f "$P" ]; then \
        FOUND="$P"; \
        break; \
      fi; \
    done; \
    if [ -z "$FOUND" ]; then \
      echo "ERROR: 未找到 MAVEN_MODULE=${MAVEN_MODULE} 的 jar，候选路径:"; \
      for P in $CANDIDATES; do echo "  - $P"; done; \
      echo "实际找到的所有 jar:"; \
      find /workspace -maxdepth 6 -name "*.jar" 2>/dev/null | head -50 || true; \
      exit 1; \
    fi; \
    cp "$FOUND" /workspace/app.jar; \
    echo "OK: 归一化 jar $FOUND → /workspace/app.jar ($(du -h /workspace/app.jar | cut -f1))"

# ── 运行时层 ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8080 8081 8082 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
