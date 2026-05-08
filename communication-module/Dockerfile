FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && mvn package -DskipTests -Dmaven.test.skip=true -q
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/communicatiemodule-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
