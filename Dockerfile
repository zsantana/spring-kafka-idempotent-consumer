# =============================================================================
# Dockerfile - Kafka Consumer Demo com Zulu OpenJDK 21
# Multi-stage build para otimização de tamanho
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build
# -----------------------------------------------------------------------------
FROM azul/zulu-openjdk:21 AS builder

WORKDIR /app

# Copiar Maven wrapper e pom.xml primeiro (cache de dependências)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Baixar dependências (camada cacheada)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copiar código fonte
COPY src src

# Build da aplicação (skip tests para build mais rápido)
RUN ./mvnw package -DskipTests -B

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM azul/zulu-openjdk-alpine:21-jre AS runtime

# Metadata
LABEL maintainer="rsantana"
LABEL application="kafka-consumer-demo"
LABEL version="1.0.0"

# Criar usuário não-root para segurança
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copiar JAR do stage de build
COPY --from=builder /app/target/*.jar app.jar

# Alterar ownership para usuário não-root
RUN chown -R appuser:appgroup /app

# Usar usuário não-root
USER appuser

# Porta da aplicação
EXPOSE 8081

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8081/actuator/health || exit 1

# JVM Configuration otimizada para containers
# - UseContainerSupport: Respeita limites de CPU/memória do container
# - MaxRAMPercentage: Usa 75% da memória disponível para heap
# - UseZGC + ZGenerational: GC de baixa latência otimizado para Java 21
# - ExitOnOutOfMemoryError: Termina o container se OOM (permite restart pelo orchestrator)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom"

# Variáveis de ambiente padrão (podem ser sobrescritas)
ENV SPRING_PROFILES_ACTIVE=default
ENV SERVER_PORT=8081

# Entrypoint com suporte a JAVA_OPTS
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
