# 通用服务镜像（D24）
#
# 四个 Spring Boot 服务共用这个文件，靠 build arg 区分要拷哪个 jar：
#   docker build -f docker/service/Dockerfile --build-arg JAR_FILE=interview-service/target/interview-service-0.1.0-SNAPSHOT.jar ...
#
# **镜像里不跑 Maven**：jar 由宿主机的 `mvn package` 产出后 COPY 进来。
# 理由有两条——
#   ① 容器内构建要再下一遍依赖（几百 MB），而宿主机的 ~/.m2 已经全了；
#   ② 16G 机器上同时跑 Maven + 四个服务 + 五个容器会把内存吃满（D22 踩过进程被回收）。
# 代价是"构建前必须先 mvn package"，这一条写在 README 的快速启动里。

FROM eclipse-temurin:21-jre

# curl 只用于 HEALTHCHECK。eclipse-temurin 基于 Ubuntu，apt 可用。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ARG JAR_FILE
COPY ${JAR_FILE} /app/app.jar

# 堆上限由 compose 的 JAVA_TOOL_OPTIONS 注入（每个服务不同，见 docker-compose.yml）。
# 这里给一个保守的默认值，防止有人直接 docker run 时按宿主机内存算堆。
ENV JAVA_TOOL_OPTIONS="-Xmx256m"

# 时区：日志与 LocalDateTime.now() 都按东八区
ENV TZ=Asia/Shanghai

EXPOSE 8080

# exec 形式（JSON array）而不是 shell 形式：shell 形式会多一层 sh 进程，
# SIGTERM 传不到 JVM，`docker compose stop` 要等 10s 超时才 kill -9。
# 而 Nacos 的优雅注销、SSE 的 onCompletion 都依赖正常的 shutdown hook。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
