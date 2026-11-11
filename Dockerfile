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

# Diretório para persistência dos logs
RUN mkdir -p /logs
VOLUME /logs

# Configurações padrão da aplicação
ENV PORT=8080
ENV METRICS_INTERVAL_S=5

EXPOSE 8080

# Copia o script de inicialização
COPY start.sh /app/start.sh

# Executa o Java como processo principal
CMD ["sh", "/app/start.sh"]
