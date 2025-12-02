#### Stage 1: Build the application
FROM amazoncorretto:21.0.7-al2023 AS build

VOLUME /tmp

# Copy the JAR file from the modules/excalibase-graphql-api/target directory
ADD /modules/excalibase-graphql-api/target/*.jar app.jar

# JVM memory optimization flags
# - Limits heap to 512MB (adjust based on your needs)
# - Uses G1GC for better memory management
# - Enables string deduplication to save heap space
# - Container support for better memory detection
ENV JAVA_OPTS="-XX:InitialRAMPercentage=50.0 \
               -XX:MaxRAMPercentage=75.0 \
               -XX:MinRAMPercentage=50.0 \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -XX:MaxMetaspaceSize=256m \
               -XX:+UseStringDeduplication \
               -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
