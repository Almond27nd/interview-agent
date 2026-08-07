# ---- Build Stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# 依赖缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 编译
COPY src ./src
RUN mvn package -DskipTests -B

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache ca-certificates tzdata

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Web 服务默认端口
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
