#### Stage 1: Build the application
FROM amazoncorretto:21.0.7-al2023 AS build

VOLUME /tmp

# Copy the JAR file from the modules/graphql/target directory
ADD /modules/graphql/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
