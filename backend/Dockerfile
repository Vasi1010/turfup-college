# Build: compile and package the Spring Boot jar. Dependencies are fetched in their own layer so code-only
# changes rebuild quickly.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw -q -B dependency:go-offline
COPY src/ src/
RUN sh mvnw -q -B -DskipTests package && cp target/backend-*.jar /app.jar

# Run: JRE only, as a non-root user, on IST (all domain times are Asia/Kolkata).
FROM eclipse-temurin:21-jre
ENV TZ=Asia/Kolkata \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Kolkata"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && groupadd --system turfup && useradd --system --gid turfup turfup
COPY --from=build /app.jar /app/app.jar
USER turfup
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
