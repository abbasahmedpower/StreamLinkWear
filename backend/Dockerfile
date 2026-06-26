FROM gradle:8.7-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
# Build the Fat Jar
RUN gradle :backend:buildFatJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
EXPOSE 8080
RUN mkdir /app
# Copy the built fat jar (adjust the path if the task produces a different name)
COPY --from=build /home/gradle/src/backend/build/libs/*-all.jar /app/ktor-backend.jar
ENTRYPOINT ["java","-jar","/app/ktor-backend.jar"]
