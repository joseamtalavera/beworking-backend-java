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
ENTRYPOINT ["java", "-jar", "app.jar"]
