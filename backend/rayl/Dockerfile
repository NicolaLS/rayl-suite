# Packages the distribution from `./gradlew :backend:rayl:installDist`; see README.md.
FROM eclipse-temurin:21-jre
COPY build/install/rayl /app
ENV HOST=0.0.0.0
CMD ["/app/bin/rayl"]
