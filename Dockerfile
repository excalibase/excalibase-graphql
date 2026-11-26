# ENGINE picks the image: "all" (default) carries every database plugin, "postgres", "mysql" or "sqlite" carries one (EXC-799).
#   docker build .                          the all-in-one image
#   docker build --build-arg ENGINE=mysql . the MySQL-only image (sqlite: the SQLite-only image)
# The slim jars come from `mvn -Pslim-<engine> package`; the all-in-one jar from a plain `mvn package`.
ARG ENGINE=all
# EXC-881: CI builds from the mirror of Docker Hub (--build-arg HUB_MIRROR=ghcr.io/excalibase/mirror/); empty pulls Docker Hub.
ARG HUB_MIRROR=

FROM ${HUB_MIRROR}eclipse-temurin:25-jre-alpine AS runtime

# Non-root runtime user so the pod can run under runAsNonRoot (SEC-H4). The
# app listens on a non-privileged port and only writes to /tmp.
RUN apk upgrade --no-cache && apk add --no-cache curl \
    && addgroup -S app && adduser -S -G app -u 1000 app

VOLUME /tmp

FROM runtime AS engine-all
ADD /modules/excalibase-graphql-api/target/excalibase-graphql-api-0.0.1-SNAPSHOT.jar /app.jar

# A slim image names its own database, so it needs no APP_DATABASE_TYPE or driver setting.
FROM runtime AS engine-postgres
ADD /modules/excalibase-graphql-api/target/excalibase-graphql-api-0.0.1-SNAPSHOT-postgres.jar /app.jar
ENV APP_DATABASE_TYPE=postgres \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver

FROM runtime AS engine-mysql
ADD /modules/excalibase-graphql-api/target/excalibase-graphql-api-0.0.1-SNAPSHOT-mysql.jar /app.jar
ENV APP_DATABASE_TYPE=mysql \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver

# A SQLite database is a file: mount a volume at /data and point SPRING_DATASOURCE_URL at jdbc:sqlite:/data/<file>.
FROM runtime AS engine-sqlite
ADD /modules/excalibase-graphql-api/target/excalibase-graphql-api-0.0.1-SNAPSHOT-sqlite.jar /app.jar
RUN mkdir /data && chown app:app /data
VOLUME /data
ENV APP_DATABASE_TYPE=sqlite \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.sqlite.JDBC

FROM engine-${ENGINE}

USER app

# --enable-native-access: the SQLite driver loads a native library (JAVA_TOOL_OPTIONS would print a line to stderr).
ENV JAVA_OPTS="--enable-native-access=ALL-UNNAMED \
               -XX:+UseG1GC \
               -XX:InitialRAMPercentage=25.0 \
               -XX:MaxRAMPercentage=60.0 \
               -XX:MinRAMPercentage=25.0 \
               -XX:MaxMetaspaceSize=256m \
               -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
