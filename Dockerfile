# Stage 1: Build Forge engine + bridge server
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY . .

# Build Forge engine modules first, then our server
RUN mvn install -pl forge-engine -am -DskipTests -q
RUN mvn package -pl forge-server -DskipTests -q

# Stage 2: Lightweight runtime
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=build /app/forge-server/target/forge-server-1.0.0-SNAPSHOT.jar app.jar

# Copy Forge card data (card definitions, editions, etc.)
COPY --from=build /app/forge-res /app/forge-res

ENV PORT=7000
ENV FORGE_RES=/app/forge-res

EXPOSE 7000

CMD ["java", "-Xmx512m", "-jar", "app.jar"]
