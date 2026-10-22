# 后端：CI 生成 app.jar，服务器仅组装运行镜像
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar app.jar
# 上传文件 + 日志（挂载卷；数据库在独立 mysql 容器）
VOLUME /app/data
EXPOSE 8080
ENV JAVA_OPTS="-Xmx512m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
