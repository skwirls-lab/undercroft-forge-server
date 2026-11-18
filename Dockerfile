# Stage 1: Build Forge engine + bridge server
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY . .

# Step 1: Build & install Forge engine modules to local Maven repo
RUN cd forge-engine && mvn install -DskipTests -q

# Step 2: Build our bridge server (depends on Forge modules from step 1)
RUN cd forge-server && mvn package -DskipTests -q

# Stage 2: Lightweight runtime
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=build /app/forge-server/target/forge-server-1.0.0-SNAPSHOT.jar app.jar

# Copy Forge card data (card definitions, editions, etc.)
COPY --from=build /app/forge-res /app/res

ENV PORT=7000
ENV FORGE_RES=/app/res
# Java heap in MB. Every running game lives in this heap, and the server derives how many
# games it accepts from it (see MAX_GAMES in the README). Raise it from the host's variables,
# not here; the container needs roughly 300 MB on top of this figure.
ENV MAX_HEAP_MB=512

EXPOSE 7000

CMD ["sh", "-c", "exec java -Xmx${MAX_HEAP_MB}m -jar app.jar"]
