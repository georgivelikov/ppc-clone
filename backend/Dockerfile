# One Dockerfile for every service:  docker build -f backend/Dockerfile --build-arg SERVICE=payments -t cutpay/payments backend
#
# Layers: the dependencies change rarely and the application classes change on every commit, so they are separate
# layers and a rebuild after a code change uploads a few kilobytes, not the whole jar.

FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY libs libs
COPY services services
RUN test -n "$SERVICE" || (echo "pass --build-arg SERVICE=<name>" && exit 1)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q ":services:${SERVICE}:bootJar" -x test \
 && mkdir /extracted \
 && java -Djarmode=tools -jar "services/${SERVICE}/build/libs/"*[!n].jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre
ARG SERVICE
# A non-root user with no shell login: the process can write only to its own temp directory.
RUN groupadd --system cutpay && useradd --system --gid cutpay --no-create-home --shell /usr/sbin/nologin cutpay
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
ENV SERVICE=${SERVICE} \
    CUTPAY_LOG_DIR=/tmp/logs \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER cutpay
# The jar's name is the service's; the layer extraction leaves it at the top.
ENTRYPOINT ["sh", "-c", "exec java -jar /app/${SERVICE}*.jar"]
