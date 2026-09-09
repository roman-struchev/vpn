FROM eclipse-temurin:25-jre
COPY server/build/libs/*.jar application.jar
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "application.jar"]
