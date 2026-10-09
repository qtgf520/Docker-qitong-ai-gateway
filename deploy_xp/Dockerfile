# 綦桐AI网关 Docker 镜像 —— 直接使用已构建的 fatJar
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 时区 + 健康检查工具
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && apt-get update -qq && apt-get install -y -qq curl >/dev/null 2>&1 \
    && rm -rf /var/lib/apt/lists/*

# 数据目录（挂载卷）
RUN mkdir -p /data/qitong

# 拷贝 fatJar
COPY qitong-gateway.jar /app/qitong-gateway.jar

EXPOSE 18080 18889

ENV WEB_PORT=18080 \
    GATEWAY_PORT=18889 \
    DB_PATH=/data/qitong/gateway.db

VOLUME ["/data/qitong"]

# 内存受限环境：限堆 512M，避免 2G 容器 OOM
CMD ["java", "-Xmx512m", "-Xms128m", "-jar", "/app/qitong-gateway.jar"]