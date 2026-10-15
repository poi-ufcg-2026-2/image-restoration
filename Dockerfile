# Etapa 1: Compilação
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY src/ ./src/
COPY build.sh .

RUN bash build.sh

# Etapa 2: Execução
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/bin ./bin
COPY images/ ./images/

EXPOSE 8080

CMD ["java", "-cp", "bin", "ImageRestorationServer"]