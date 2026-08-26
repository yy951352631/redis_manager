FROM maven:3.9.9-eclipse-temurin-8 AS build

WORKDIR /build
COPY pom.xml ./
COPY cachecloud-custom/pom.xml cachecloud-custom/pom.xml
COPY cachecloud-web/pom.xml cachecloud-web/pom.xml

COPY cachecloud-custom cachecloud-custom
COPY cachecloud-web cachecloud-web
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package

FROM tomcat:9.0.78-jdk8-temurin

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openssh-client sshpass unzip \
    && rm -rf /var/lib/apt/lists/* /usr/local/tomcat/webapps/*

COPY --from=build /build/cachecloud-web/target/cachecloud-web.war /usr/local/tomcat/webapps/ROOT.war

EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=90s --retries=10 \
  CMD curl --fail --silent --output /dev/null http://127.0.0.1:8080/api/v1/health || exit 1

CMD ["catalina.sh", "run"]
