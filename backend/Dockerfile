# Build with the project's Gradle wrapper on JDK 21 LTS to match the backend's
# Java 21 source/target level (see backend/build.gradle.kts). The runtime image
# is 21 LTS too — a long support window, unlike the short-lived non-LTS 24.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew buildFatJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/homebase-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
