ARG MAVEN_IMAGE=m.daocloud.io/docker.io/library/maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=m.daocloud.io/docker.io/library/eclipse-temurin:17-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN mvn -B -pl backend -am package -DskipTests

FROM ${JRE_IMAGE}
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl default-mysql-client \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/backend/target/backend-1.0.0-rc.1.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=20 CMD curl -fsS http://localhost:8080/actuator/health | grep -q 'UP' || exit 1
ENTRYPOINT ["java","-jar","app.jar"]
