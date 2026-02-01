#!/bin/bash

set -e

echo "=========================================="
echo "Kafka Consumer Demo - Startup Script"
echo "=========================================="
echo ""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

check_service_health() {
    local service=$1
    local max_attempts=30
    local attempt=1
    
    echo -n "Waiting for $service to be healthy..."
    
    while [ $attempt -le $max_attempts ]; do
        if docker-compose ps | grep $service | grep -q "healthy"; then
            echo -e " ${GREEN}✓${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    echo -e " ${RED}✗${NC}"
    echo -e "${RED}Error: $service did not become healthy${NC}"
    return 1
}

echo "1. Checking Docker..."
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}Error: Docker is not running${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Docker is running${NC}"
echo ""

echo "2. Stopping existing containers..."
docker-compose down > /dev/null 2>&1 || true
echo -e "${GREEN}✓ Containers stopped${NC}"
echo ""

echo "3. Starting infrastructure..."
docker-compose up -d postgres redis zookeeper kafka
echo ""

echo "4. Waiting for services to be ready..."
check_service_health "kafka-postgres" || exit 1
check_service_health "kafka-redis" || exit 1
check_service_health "kafka-zookeeper" || exit 1
check_service_health "kafka-broker" || exit 1
echo ""

echo "5. Creating Kafka topics..."
docker exec kafka-broker kafka-topics \
    --create \
    --if-not-exists \
    --topic high-volume-topic \
    --partitions 10 \
    --replication-factor 1 \
    --bootstrap-server localhost:9092 \
    > /dev/null 2>&1

docker exec kafka-broker kafka-topics \
    --create \
    --if-not-exists \
    --topic high-volume-topic-dlq \
    --partitions 3 \
    --replication-factor 1 \
    --bootstrap-server localhost:9092 \
    > /dev/null 2>&1

echo -e "${GREEN}✓ Kafka topics created${NC}"
echo ""

echo "6. Starting monitoring and admin services..."
docker-compose up -d kafka-ui pgadmin redisinsight prometheus grafana
echo -e "${GREEN}✓ Monitoring and admin services started${NC}"
echo ""

if [ ! -f "target/kafka-consumer-demo-1.0.0.jar" ]; then
    echo "7. Building application..."
    mvn clean package -DskipTests
    echo -e "${GREEN}✓ Application built${NC}"
    echo ""
else
    echo "7. Application already built"
    echo -e "${GREEN}✓ Skipping build${NC}"
    echo ""
fi

echo "=========================================="
echo "All services are running!"
echo "=========================================="
echo ""
echo "Access URLs:"
echo "  - Application Health:  http://localhost:8081/actuator/health"
echo "  - Application Metrics: http://localhost:8081/actuator/prometheus"
echo "  - Swagger UI:          http://localhost:8081/swagger-ui.html"
echo "  - Kafka UI:            http://localhost:8090"
echo "  - pgAdmin:             http://localhost:5050 (admin@admin.com/admin)"
echo "  - RedisInsight:        http://localhost:5540"
echo "  - Prometheus:          http://localhost:9090"
echo "  - Grafana:             http://localhost:3000 (admin/admin)"
echo ""
echo "Database connections:"
echo "  - PostgreSQL: localhost:5432 (postgres/postgres)"
echo "  - Redis:      localhost:6379"
echo "  - Kafka:      localhost:9092 (external) / localhost:29092 (internal)"
echo ""
echo "To start the Spring Boot application, run:"
echo -e "${YELLOW}  java -jar target/kafka-consumer-demo-1.0.0.jar${NC}"
echo "or"
echo -e "${YELLOW}  mvn spring-boot:run${NC}"
echo ""
echo "To produce test messages, run:"
echo -e "${YELLOW}  python3 scripts/produce_messages.py${NC}"
echo ""
echo "To view logs:"
echo -e "${YELLOW}  docker-compose logs -f [service-name]${NC}"
echo ""
echo "To stop all services:"
echo -e "${YELLOW}  docker-compose down${NC}"
echo ""
echo "=========================================="
