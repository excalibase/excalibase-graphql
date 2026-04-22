FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache curl

VOLUME /tmp

ADD /modules/excalibase-graphql-api/target/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseZGC \
               -XX:+ZGenerational \
               -XX:InitialRAMPercentage=50.0 \
               -XX:MaxRAMPercentage=75.0 \
               -XX:MinRAMPercentage=50.0 \
               -XX:MaxMetaspaceSize=256m \
               -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
