# ── Content Ops 单体服务镜像（多阶段构建）──
# 构建上下文必须是仓库根目录（需要同时访问 content-ops-configs 父 POM 与 content-ops-server 模块）：
#   docker build -f Dockerfile -t content-ops-server .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY content-ops-configs ./content-ops-configs
COPY content-ops-server ./content-ops-server

RUN mvn -B -f content-ops-configs/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/content-ops-server/content-ops-server/target/content-ops-server-1.0.0.jar app.jar

EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
