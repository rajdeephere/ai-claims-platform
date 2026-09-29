# syntax=docker/dockerfile:1.7
#
# docker build -t ai-claims-platform ./backend
#
# Stage 1 builds the jar with Maven. Stage 2 runs it on a small JRE image as a non-root user, from Spring
# Boot's layered jar: dependencies change rarely and sit in lower layers, so a code change only rebuilds
# the small application layer.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
# Tests run in CI before the image is built; the cache mount keeps ~/.m2 between builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests \
 && cp target/ai-claims-platform-*.jar /app.jar
RUN java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-alpine
LABEL org.opencontainers.image.title="ai-claims-platform"

# fonts: PDFBox renders pages of scanned PDFs for the vision model (Alpine ships with none)
RUN apk add --no-cache fontconfig ttf-dejavu \
 && addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./

USER app

# Render free tier: 512 MB for the whole container.
#  MaxRAMPercentage=75  heap sized from the container limit (about 384 MB), the rest for metaspace/threads
#  SerialGC             least memory overhead on a single small CPU
#  Xss512k              smaller thread stacks (Tomcat keeps a pool of threads)
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
