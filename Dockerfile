# Mock Service Dockerfile
# 构建: docker build -t mock-service:1.0.0 .
# 运行: docker run -p 8080:8080 mock-service:1.0.0
#
# 挂载外部配置实现热加载:
# docker run -p 8080:8080 \
#   -e JAVA_OPTS="-Dmock.watch-path=file:/config/mock-endpoints.yml" \
#   -v ./mock-endpoints.yml:/config/mock-endpoints.yml \
#   mock-service:1.0.0

FROM eclipse-temurin:8-jre

LABEL maintainer="mock-team"
LABEL description="统一Mock服务 — 身份认证三方接口模拟工具"

WORKDIR /app

# 复制 JAR
COPY target/mock-boot-1.0.0.jar /app/app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
