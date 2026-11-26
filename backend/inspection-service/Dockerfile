FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
# ~/.m2 is a BuildKit cache mount, so a dropped download resumes on the next build instead of starting over.
# Named file locks keep services that build in parallel from corrupting the shared local repository.
ENV MVN_OPTS="-B -Daether.connector.http.retryHandler.count=10 -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"
COPY pom.xml ./pom.xml
COPY shared/java-eventing/pom.xml ./shared/java-eventing/pom.xml
COPY shared/java-web-error/pom.xml ./shared/java-web-error/pom.xml
COPY shared/java-business-calendar/pom.xml ./shared/java-business-calendar/pom.xml
COPY shared/java-arch-rules/pom.xml ./shared/java-arch-rules/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY user-service/pom.xml ./user-service/pom.xml
COPY product-service/pom.xml ./product-service/pom.xml
COPY contract-service/pom.xml ./contract-service/pom.xml
COPY escrow-service/pom.xml ./escrow-service/pom.xml
COPY reputation-service/pom.xml ./reputation-service/pom.xml
COPY inspection-service/pom.xml ./inspection-service/pom.xml
COPY bank-service/pom.xml ./bank-service/pom.xml
COPY audit-service/pom.xml ./audit-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn $MVN_OPTS dependency:go-offline -pl inspection-service -am -q
# Shared library modules the service may depend on; -am builds them in the same reactor.
COPY shared/java-eventing/src ./shared/java-eventing/src
COPY shared/java-web-error/src ./shared/java-web-error/src
COPY shared/java-business-calendar/src ./shared/java-business-calendar/src
COPY shared/java-arch-rules/src ./shared/java-arch-rules/src
COPY inspection-service/src ./inspection-service/src
RUN --mount=type=cache,target=/root/.m2 mvn $MVN_OPTS clean package -pl inspection-service -am -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/inspection-service/target/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
