FROM eclipse-temurin:21-jre-jammy

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"

RUN groupadd --system planus \
    && useradd --system --gid planus --no-create-home planus

WORKDIR /app

COPY --chown=planus:planus build/libs/app.jar app.jar

USER planus

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
