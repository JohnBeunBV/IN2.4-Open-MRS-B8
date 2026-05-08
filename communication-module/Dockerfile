FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn package -DskipTests -Dmaven.test.skip=true -q
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/communicatiemodule-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
