# The jar is unpacked once at build time: the JVM then reads plain jar files from disk instead of
# searching a jar nested inside a jar on every start, which is slow on a small CPU allowance.
FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /build
COPY modules/excalibase-graphql-api/target/*.jar ./
# target/ can also hold the -javadoc and -sources jars; extract the application jar only.
RUN rm -f *-javadoc.jar *-sources.jar && mv *.jar app.jar && java -Djarmode=tools -jar app.jar extract --destination /app

FROM eclipse-temurin:25-jre-alpine

# Non-root runtime user so the pod can run under runAsNonRoot (SEC-H4). The
# app listens on a non-privileged port and only writes to /tmp.
RUN apk upgrade --no-cache && apk add --no-cache curl \
    && addgroup -S app && adduser -S -G app -u 1000 app

VOLUME /tmp

COPY --from=extract /app/ /app/

USER app

ENV JAVA_OPTS="-XX:+UseG1GC \
               -XX:InitialRAMPercentage=25.0 \
               -XX:MaxRAMPercentage=60.0 \
               -XX:MinRAMPercentage=25.0 \
               -XX:MaxMetaspaceSize=256m \
               -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
