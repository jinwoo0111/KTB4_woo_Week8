FROM eclipse-temurin:21-jdk-alpine-3.22 AS builder

WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src

RUN ./gradlew clean test bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine-3.22 AS runtime

RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

RUN mkdir -p /app/uploads \
	&& chown -R spring:spring /app

COPY --from=builder --chown=spring:spring \
    /workspace/build/libs/community-0.0.1-SNAPSHOT.jar \
    /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    UPLOAD_PATH=/app/uploads

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
