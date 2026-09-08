FROM eclipse-temurin:25-jre-alpine

# Non-root runtime user so the pod can run under runAsNonRoot (SEC-H4). The
# app listens on a non-privileged port and only writes to /tmp.
RUN apk add --no-cache curl \
    && addgroup -S app && adduser -S -G app -u 1000 app

VOLUME /tmp

ADD /modules/excalibase-graphql-api/target/*.jar /app.jar

USER app

ENV JAVA_OPTS="-XX:+UseZGC \
               -XX:+ZGenerational \
               -XX:InitialRAMPercentage=50.0 \
               -XX:MaxRAMPercentage=75.0 \
               -XX:MinRAMPercentage=50.0 \
               -XX:MaxMetaspaceSize=256m \
               -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
