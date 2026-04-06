# Build with the Maven wrapper, run on a slim JRE. No local Java needed to run the stack.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
# Dependencies first, so code changes don't re-download them.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/ledger-service-*.jar /app.jar

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 ledger
USER ledger
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
