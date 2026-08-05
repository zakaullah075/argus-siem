# Build stage. Kept separate so the JDK, Maven and the source tree never reach
# the runtime image — a compiler in production is attack surface, not a feature.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies resolve in their own layer, so a source-only change does not
# re-download the world on every build.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Split the fat jar into layers ordered by how often they change. Dependencies
# rarely change and stay cached; application classes change every build.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted


FROM eclipse-temurin:21-jre-alpine AS runtime

# Never run as root. A container escape from an unprivileged process is a much
# smaller problem than one from uid 0.
RUN addgroup -S argus && adduser -S argus -G argus

WORKDIR /app

COPY --from=build --chown=argus:argus /build/extracted/dependencies/ ./
COPY --from=build --chown=argus:argus /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=argus:argus /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=argus:argus /build/extracted/application/ ./

USER argus

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the container's
# memory limit, so the same image behaves correctly in a 512MB free tier and a
# 4GB production instance.
#
# SerialGC because G1's bookkeeping threads cost more than they return below
# roughly 1GB of heap, which is where this runs on a free tier.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
