# Build with the project's Gradle wrapper (9.4.1) on JDK 24 to match the
# backend's Java 24 source/target level. The runtime image must also be 24,
# otherwise the Java 24 bytecode won't load.
FROM eclipse-temurin:24-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew buildFatJar --no-daemon

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /app/build/libs/homebase-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
