.PHONY: run build test infra-up infra-down clean package

# 运行项目
run:
	mvn spring-boot:run

# 编译（跳过测试）
build:
	mvn compile

# 打包
package:
	mvn package -DskipTests

# 运行测试
test:
	mvn test

# 启动基础设施（Milvus + Redis + MySQL）
infra-up:
	docker-compose up -d

# 停止基础设施
infra-down:
	docker-compose down

# 查看基础设施状态
infra-status:
	docker-compose ps

# Docker 构建
docker-build:
	docker build -t interview-agent-java .

# Docker 运行
docker-run:
	docker run -p 9090:9090 --env-file .env interview-agent-java

# 清理编译产物
clean:
	mvn clean
