# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
LABEL org.opencontainers.image.title="bankdemo" \
      org.opencontainers.image.description="Banco Kokunas - mortgage & transfers demo (IBM Concert remediation lifecycle demo)" \
      org.opencontainers.image.source="https://github.com/kokunas/java-app-cve" \
      app.kubernetes.io/name="bankdemo" \
      app.kubernetes.io/part-of="banco-kokunas"

RUN groupadd -r bankdemo && useradd -r -g bankdemo bankdemo
WORKDIR /app
COPY --from=build /build/target/bankdemo.jar app.jar
USER bankdemo

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
