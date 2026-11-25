# Keycloak with the AgriContract registration-role provider and login theme (D47). Both are built here so the stack
# needs no local Maven or Node; neither is part of the backend reactor.
FROM maven:3.9-eclipse-temurin-17 AS spi
WORKDIR /build
COPY role-spi/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY role-spi/src src
RUN mvn -q -B package

# The Keycloakify theme (frontend-keycloak-theme/, compose build context "theme"). keycloakify packs the jar with Maven.
FROM node:22-bookworm AS theme
RUN apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY --from=theme-src package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=theme-src index.html tsconfig.json tsconfig.node.json vite.config.ts ./
COPY --from=theme-src src src
RUN npm run build-keycloak-theme

FROM quay.io/keycloak/keycloak:24.0.4
COPY --from=spi /build/target/agricontract-keycloak-role-spi.jar /opt/keycloak/providers/
COPY --from=theme /build/dist_keycloak/keycloak-theme-for-kc-22-to-25.jar /opt/keycloak/providers/
