#### Stage 1: Build the application
FROM amazoncorretto:21.0.7-al2023 as build

VOLUME /tmp

ADD /target/*.jar app.jar

ENTRYPOINT java -jar *.jar
