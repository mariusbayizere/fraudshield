# The FraudShield API image (C.1 fraudshield-api): the ingestion and decision path with staff
# identity, administration, the analyst API and audit, as one Spring Boot process.
#
# Build from the repository root: the backend's build reads contracts/ and the country packs.
#   docker build --file backend/Dockerfile .
# Its ignore file is backend/Dockerfile.dockerignore (BuildKit reads it for this Dockerfile only).
#
# The runtime matches infrastructure/k8s/base/api.yaml: user and group 10001, a read-only root
# filesystem (writable /tmp and spool mounted by the platform), ports 8080 (API) and 8081
# (management). Base images are pinned by digest; their tags are recorded beside each.

# eclipse-temurin:21.0.12.1_1-jdk-noble
FROM eclipse-temurin@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS build
# The Maven wrapper (only-script mode) unpacks Maven's .zip with unzip, which the JDK image lacks.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY backend/ backend/
COPY contracts/openapi/ contracts/openapi/
COPY contracts/proto/ contracts/proto/
COPY contracts/kafka/schemas/ contracts/kafka/schemas/
COPY dataset/generator/params/countries/ dataset/generator/params/countries/
WORKDIR /src/backend
# Tests, coverage and static analysis run in CI's java job on the same commit; the image build
# only compiles and packages.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl ingest -am package \
      -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true \
      -Dspotless.check.skip=true
# Split into layers by how often they change; the application jar finds its libraries in lib/
# through its manifest's Class-Path. A layer can be empty, so each directory is made to exist.
RUN java -Djarmode=tools -jar ingest/target/fraudshield-ingest-0.1.0-SNAPSHOT.jar \
      extract --layers --destination /extracted \
 && mkdir -p /extracted/dependencies /extracted/spring-boot-loader \
      /extracted/snapshot-dependencies /extracted/application

# eclipse-temurin:21.0.12.1_1-jre-noble
FROM eclipse-temurin@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
RUN groupadd --system --gid 10001 fraudshield \
 && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin fraudshield \
 && install --directory --owner 10001 --group 10001 /var/lib/fraudshield/spool
WORKDIR /app
# Least-changing layers first, so a code change rebuilds only the last one.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
# The heap follows the container's memory limit; the rest is left to metaspace, threads and the
# spool's buffers.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "-Djava.io.tmpdir=/tmp", "-jar", "/app/fraudshield-ingest-0.1.0-SNAPSHOT.jar"]
