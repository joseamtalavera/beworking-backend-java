# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# 1) cache dependencies
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN mvn dependency:go-offline -B

# 2) build app
COPY src src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
# Startup tuning (#267): on Fargate the long cold start is dominated by JIT and
# class loading. -XX:TieredStopAtLevel=1 stops at the C1 compiler so the JVM
# isn't running expensive C2 compilation during boot (cuts startup markedly;
# negligible impact on this I/O-bound workload). JMX off trims a bit more.
# Container-aware heap so we use the Fargate memory limit, not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -Dspring.jmx.enabled=false"
ENTRYPOINT ["java", "-jar", "app.jar"]
