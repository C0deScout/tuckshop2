# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build
COPY src/ src/
RUN javac --release 17 -d classes src/*.java

FROM eclipse-temurin:17-jre-jammy AS runtime

ARG SQLITE_JDBC_VERSION=3.42.0.0
ARG SQLITE_JDBC_BASE=https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl sqlite3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 tuckshop \
    && useradd --uid 10001 --gid tuckshop --no-create-home --shell /usr/sbin/nologin tuckshop

WORKDIR /app
COPY --from=build /build/classes/ /app/classes/

RUN mkdir -p /app/lib /data \
    && curl --fail --silent --show-error --location \
      "${SQLITE_JDBC_BASE}/${SQLITE_JDBC_VERSION}/sqlite-jdbc-${SQLITE_JDBC_VERSION}.jar" \
      --output /app/lib/sqlite-jdbc.jar \
    && curl --fail --silent --show-error --location \
      "${SQLITE_JDBC_BASE}/${SQLITE_JDBC_VERSION}/sqlite-jdbc-${SQLITE_JDBC_VERSION}.jar.sha1" \
      --output /tmp/sqlite-jdbc.jar.sha1 \
    && echo "$(cat /tmp/sqlite-jdbc.jar.sha1)  /app/lib/sqlite-jdbc.jar" | sha1sum --check --strict - \
    && rm /tmp/sqlite-jdbc.jar.sha1 \
    && chown -R tuckshop:tuckshop /app /data

COPY --chown=tuckshop:tuckshop wwwroot/ /app/wwwroot/

USER 10001:10001
ENV TUCK_PORT=5000 \
    TUCK_BIND=0.0.0.0 \
    TUCK_DB=/data/tuck.db \
    TUCK_WEB=/app/wwwroot \
    TUCK_SECURE_COOKIES=true \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 5000
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl --fail --silent --show-error http://127.0.0.1:5000/api/health >/dev/null || exit 1

ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/sqlite-jdbc.jar", "TuckShop"]
