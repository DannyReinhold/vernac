# Build once with ./mvnw clean package, then run the same artifact in each environment.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/application.jar /app/application.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
