# Package the JAR verified by Maven/CI; no credentials or source build cache in the image.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --chmod=0444 backend/target/finstep-api-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080 9090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
