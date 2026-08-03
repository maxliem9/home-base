# Build with the project's Gradle wrapper on JDK 21 LTS to match the backend's
# Java 21 source/target level (see backend/build.gradle.kts). The runtime image
# is 21 LTS too — a long support window, unlike the short-lived non-LTS 24.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true
COPY src ./src
# Build-Version (#626). Der Repo-Root (und damit die VERSION-Datei) liegt nicht im Build-Context
# (context: ./backend), deshalb reicht CI Version + Commit als Build-Args durch; ohne sie baut ein
# lokales `docker build ./backend` als 0.0.0-dev. Bewusst NACH dem Dependency-Layer, damit ein
# neuer Commit-SHA den teuren Dependency-Cache nicht invalidiert.
ARG APP_VERSION=""
ARG GIT_SHA=""
ENV APP_VERSION=$APP_VERSION
ENV GIT_SHA=$GIT_SHA
RUN ./gradlew buildFatJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/homebase-backend.jar app.jar
# Run unprivileged to limit the blast radius of an RCE (issue #61): no root in
# the container, and note-image writes land under a non-root-owned uploads dir.
# A *fresh* named volume inherits /data/uploads' ownership from the image, so the
# app (uid 10001) can write; an *existing* root-owned volume needs a one-time
# `chown -R 10001:10001` of its uploads on the host.
RUN groupadd -g 10001 app \
    && useradd -u 10001 -g app -M -s /usr/sbin/nologin app \
    && mkdir -p /data/uploads \
    && chown -R app:app /app /data/uploads
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
