#### Stage 1: Build the application
FROM amazoncorretto:21.0.7-al2023 AS build

VOLUME /tmp

# Copy the JAR file from the modules/excalibase-graphql-api/target directory
ADD /modules/excalibase-graphql-api/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
