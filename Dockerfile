#### Stage 1: Build the application
FROM amazoncorretto:25-al2023 AS build

VOLUME /tmp

# Copy the JAR file from the modules/excalibase-graphql-api/target directory
ADD /modules/excalibase-graphql-api/target/*.jar app.jar

# JVM flags — ZGC (generational, low-latency), percentage-based heap
ENV JAVA_OPTS="-XX:+UseZGC \
               -XX:+ZGenerational \
               -XX:InitialRAMPercentage=50.0 \
               -XX:MaxRAMPercentage=75.0 \
               -XX:MinRAMPercentage=50.0 \
               -XX:MaxMetaspaceSize=256m \
               -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
