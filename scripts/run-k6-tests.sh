#!/bin/bash

# =============================================================================
# K6 Kafka Load Test Runner - Kafka Consumer Demo
# =============================================================================
# Este script executa testes de carga usando K6 enviando mensagens diretamente
# para o tópico Kafka (requer xk6-kafka extension)
# 
# Uso:
#   ./run-k6-tests.sh [comando] [opções]
#
# Comandos:
#   smoke        - Teste rápido de verificação (30s, 1 VU)
#   load         - Teste de carga normal (5min, até 10 VUs)
#   stress       - Teste de stress (10min, até 100 VUs)
#   spike        - Teste de pico súbito (2min, 100 VUs instantâneos)
#   max          - Teste de throughput máximo (10k msg/s)
#   full         - Executa todos os cenários em sequência
#   custom       - Teste customizado com parâmetros
#
# Opções:
#   -b, --brokers  Kafka brokers (padrão: localhost:9092)
#   -t, --topic    Kafka topic (padrão: high-volume-topic)
#   -o, --output   Diretório para resultados (padrão: ./k6-results)
#   -d, --docker   Executa K6 via Docker
#   -h, --help     Mostra esta ajuda
# =============================================================================

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configurações padrão
KAFKA_BROKERS="${KAFKA_BROKERS:-localhost:9092}"
KAFKA_TOPIC="${KAFKA_TOPIC:-high-volume-topic}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/k6-load-test.js"
OUTPUT_DIR="${SCRIPT_DIR}/../k6-results"
USE_DOCKER=false
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Imagem Docker com extensão Kafka
K6_DOCKER_IMAGE="mostafamoradian/xk6-kafka:latest"

# Função de ajuda
show_help() {
    echo -e "${BLUE}K6 Kafka Load Test Runner - Kafka Consumer Demo${NC}"
    echo ""
    echo "Uso: $0 [comando] [opções]"
    echo ""
    echo "Comandos:"
    echo "  smoke     Teste rápido de verificação (30s, 1 VU)"
    echo "  load      Teste de carga normal (5min, até 10 VUs)"
    echo "  stress    Teste de stress (10min, até 100 VUs)"
    echo "  spike     Teste de pico súbito (2min, 100 VUs instantâneos)"
    echo "  max       Teste de throughput máximo (10k msg/s, 2min)"
    echo "  full      Executa todos os cenários em sequência (~22min)"
    echo "  custom    Teste customizado (requer parâmetros adicionais)"
    echo ""
    echo "Opções:"
    echo "  -b, --brokers BROKERS  Kafka brokers (padrão: $KAFKA_BROKERS)"
    echo "  -t, --topic TOPIC      Kafka topic (padrão: $KAFKA_TOPIC)"
    echo "  -o, --output DIR       Diretório para resultados (padrão: ./k6-results)"
    echo "  -d, --docker           Executa K6 via Docker (recomendado)"
    echo "  -v, --vus NUM          Número de VUs para teste custom"
    echo "  --duration TIME        Duração para teste custom (ex: 5m, 30s)"
    echo "  -h, --help             Mostra esta ajuda"
    echo ""
    echo "Exemplos:"
    echo "  $0 smoke"
    echo "  $0 load -b kafka:29092 -t my-topic"
    echo "  $0 custom -v 50 --duration 2m"
    echo "  $0 full -d"
    echo ""
    echo -e "${YELLOW}Nota: Requer xk6-kafka extension. Use -d para Docker (recomendado).${NC}"
}

# Verifica se K6 está instalado com extensão Kafka
check_k6() {
    if $USE_DOCKER; then
        if ! docker info > /dev/null 2>&1; then
            echo -e "${RED}Erro: Docker não está rodando${NC}"
            exit 1
        fi
        echo -e "${GREEN}✓ Usando K6 via Docker (${K6_DOCKER_IMAGE})${NC}"
    else
        if ! command -v k6 &> /dev/null; then
            echo -e "${RED}K6 não encontrado.${NC}"
            echo -e "${YELLOW}Para testes Kafka, recomenda-se usar Docker:${NC}"
            echo "  $0 $COMMAND -d"
            echo ""
            echo "Ou instale K6 com extensão Kafka:"
            echo "  go install go.k6.io/xk6/cmd/xk6@latest"
            echo "  xk6 build --with github.com/mostafa/xk6-kafka@latest"
            exit 1
        fi
        # Verifica se tem a extensão kafka
        if ! k6 version 2>&1 | grep -q "kafka"; then
            echo -e "${YELLOW}Aviso: K6 instalado mas sem extensão Kafka${NC}"
            echo -e "${YELLOW}Usando Docker para garantir compatibilidade...${NC}"
            USE_DOCKER=true
        else
            echo -e "${GREEN}✓ K6 com extensão Kafka instalado${NC}"
        fi
    fi
}

# Verifica se Kafka está acessível
check_kafka() {
    echo -n "Verificando Kafka em $KAFKA_BROKERS... "
    
    local broker_host=$(echo $KAFKA_BROKERS | cut -d: -f1)
    local broker_port=$(echo $KAFKA_BROKERS | cut -d: -f2)
    
    if nc -z -w5 $broker_host $broker_port 2>/dev/null; then
        echo -e "${GREEN}✓ Acessível${NC}"
    else
        echo -e "${RED}✗ Inacessível${NC}"
        echo -e "${YELLOW}Verifique se o Kafka está rodando em $KAFKA_BROKERS${NC}"
        echo "Para iniciar via docker-compose:"
        echo "  docker-compose up -d kafka"
        exit 1
    fi
}

# Cria diretório de output
setup_output() {
    mkdir -p "$OUTPUT_DIR"
    chmod 755 "$OUTPUT_DIR"
    echo -e "${GREEN}✓ Diretório de resultados: $OUTPUT_DIR${NC}"
}

# Executa K6
run_k6() {
    local scenario=$1
    local extra_args="${@:2}"
    local output_file="${OUTPUT_DIR}/k6-kafka-${scenario}-${TIMESTAMP}"
    
    echo ""
    echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  Executando: $scenario${NC}"
    echo -e "${BLUE}  Brokers: $KAFKA_BROKERS${NC}"
    echo -e "${BLUE}  Topic: $KAFKA_TOPIC${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo ""
    
    local k6_cmd
    
    if $USE_DOCKER; then
        k6_cmd="docker run --rm -i \
            --network host \
            --user $(id -u):$(id -g) \
            -v ${SCRIPT_DIR}:/scripts:ro \
            -v ${OUTPUT_DIR}:/results \
            -e KAFKA_BROKERS=${KAFKA_BROKERS} \
            -e KAFKA_TOPIC=${KAFKA_TOPIC} \
            -e SCENARIO=${scenario} \
            ${K6_DOCKER_IMAGE} run \
            --out json=/results/k6-kafka-${scenario}-${TIMESTAMP}.json \
            --summary-export=/results/k6-kafka-${scenario}-${TIMESTAMP}-summary.json \
            $extra_args \
            /scripts/k6-load-test.js"
    else
        k6_cmd="k6 run \
            --out json=${output_file}.json \
            --summary-export=${output_file}-summary.json \
            -e KAFKA_BROKERS=${KAFKA_BROKERS} \
            -e KAFKA_TOPIC=${KAFKA_TOPIC} \
            -e SCENARIO=${scenario} \
            $extra_args \
            ${K6_SCRIPT}"
    fi
    
    echo "Comando: $k6_cmd"
    echo ""
    
    eval $k6_cmd
    
    echo ""
    echo -e "${GREEN}✓ Teste $scenario concluído${NC}"
    echo -e "Resultados salvos em: ${output_file}*"
}

# Cenários de teste
run_smoke() {
    run_k6 "smoke"
}

run_load() {
    run_k6 "load"
}

run_stress() {
    run_k6 "stress"
}

run_spike() {
    run_k6 "spike"
}

run_max() {
    run_k6 "max"
}

run_full() {
    echo -e "${YELLOW}Executando todos os cenários (~22 minutos)${NC}"
    run_k6 "all"
}

run_custom() {
    local vus=${CUSTOM_VUS:-10}
    local duration=${CUSTOM_DURATION:-1m}
    
    run_k6 "custom" "--vus $vus --duration $duration"
}

# =============================================================================
# Main
# =============================================================================

# Parse argumentos
COMMAND=""
CUSTOM_VUS=10
CUSTOM_DURATION="1m"

while [[ $# -gt 0 ]]; do
    case $1 in
        smoke|load|stress|spike|max|full|custom)
            COMMAND=$1
            shift
            ;;
        -b|--brokers)
            KAFKA_BROKERS="$2"
            shift 2
            ;;
        -t|--topic)
            KAFKA_TOPIC="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -d|--docker)
            USE_DOCKER=true
            shift
            ;;
        -v|--vus)
            CUSTOM_VUS="$2"
            shift 2
            ;;
        --duration)
            CUSTOM_DURATION="$2"
            shift 2
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo -e "${RED}Opção desconhecida: $1${NC}"
            show_help
            exit 1
            ;;
    esac
done

# Verifica comando
if [ -z "$COMMAND" ]; then
    echo -e "${RED}Erro: Comando não especificado${NC}"
    echo ""
    show_help
    exit 1
fi

# Header
echo ""
echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║       K6 Kafka Load Test - Direct to Topic                 ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "Configurações:"
echo "  Kafka Brokers: $KAFKA_BROKERS"
echo "  Kafka Topic:   $KAFKA_TOPIC"
echo "  Output:        $OUTPUT_DIR"
echo "  Docker:        $USE_DOCKER"
echo "  Comando:       $COMMAND"
echo ""

# Executa verificações
check_k6
check_kafka
setup_output

# Executa comando
case $COMMAND in
    smoke)
        run_smoke
        ;;
    load)
        run_load
        ;;
    stress)
        run_stress
        ;;
    spike)
        run_spike
        ;;
    max)
        run_max
        ;;
    full)
        run_full
        ;;
    custom)
        run_custom
        ;;
esac

echo ""
echo -e "${GREEN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                    Teste Concluído!                        ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "Para visualizar os resultados:"
echo "  cat ${OUTPUT_DIR}/k6-kafka-${COMMAND}-${TIMESTAMP}-summary.json | jq ."
echo ""
echo "Métricas importantes:"
echo "  - kafka_messages_sent:     Total de mensagens enviadas"
echo "  - kafka_produce_latency:   Latência de produção (p95, p99)"
echo "  - kafka_throughput_bytes:  Throughput em bytes"
echo "  - success_rate:            Taxa de sucesso"
echo ""
