import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { Writer, SchemaRegistry, SCHEMA_TYPE_STRING } from 'k6/x/kafka';
import encoding from 'k6/encoding';

// =============================================================================
// Configuração Kafka
// =============================================================================
const KAFKA_BROKERS = __ENV.KAFKA_BROKERS || 'localhost:9092';
const KAFKA_TOPIC = __ENV.KAFKA_TOPIC || 'high-volume-topic';
const SCENARIO = __ENV.SCENARIO || 'all'; // smoke, load, stress, spike, max, all

// Configuração do Writer (Producer)
const writer = new Writer({
    brokers: KAFKA_BROKERS.split(','),
    topic: KAFKA_TOPIC,
    autoCreateTopic: true,
});

// Helper para converter string para bytes (base64)
function stringToBytes(str) {
    return encoding.b64encode(str);
}

// Métricas customizadas
const messagesSent = new Counter('kafka_messages_sent');
const messagesFailedCounter = new Counter('kafka_messages_failed');
const kafkaLatency = new Trend('kafka_produce_latency', true);
const successRate = new Rate('success_rate');
const throughput = new Counter('kafka_throughput_bytes');

// =============================================================================
// Definição de cenários
// =============================================================================
const allScenarios = {
    smoke: {
        executor: 'constant-vus',
        vus: 1,
        duration: '30s',
        tags: { scenario: 'smoke' },
        exec: 'smokeTest',
    },
    load: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
            { duration: '1m', target: 10 },
            { duration: '3m', target: 10 },
            { duration: '1m', target: 0 },
        ],
        tags: { scenario: 'load' },
        exec: 'loadTest',
    },
    stress: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
            { duration: '2m', target: 50 },
            { duration: '5m', target: 50 },
            { duration: '2m', target: 100 },
            { duration: '1m', target: 0 },
        ],
        tags: { scenario: 'stress' },
        exec: 'stressTest',
    },
    spike: {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
            { duration: '10s', target: 100 },
            { duration: '1m', target: 100 },
            { duration: '10s', target: 0 },
        ],
        tags: { scenario: 'spike' },
        exec: 'spikeTest',
    },
    max: {
        executor: 'constant-arrival-rate',
        rate: 10000,
        timeUnit: '1s',
        duration: '2m',
        preAllocatedVUs: 50,
        maxVUs: 200,
        tags: { scenario: 'max-throughput' },
        exec: 'maxThroughputTest',
    },
};

// Seleciona cenários baseado na variável de ambiente
function getScenarios() {
    if (SCENARIO === 'all') {
        // Adiciona startTime para execução sequencial
        return {
            smoke: { ...allScenarios.smoke, startTime: '0s' },
            load: { ...allScenarios.load, startTime: '35s' },
            stress: { ...allScenarios.stress, startTime: '6m' },
            spike: { ...allScenarios.spike, startTime: '17m' },
            max: { ...allScenarios.max, startTime: '19m' },
        };
    }
    
    if (allScenarios[SCENARIO]) {
        return { [SCENARIO]: allScenarios[SCENARIO] };
    }
    
    // Fallback para load test
    console.warn(`Unknown scenario: ${SCENARIO}, falling back to 'load'`);
    return { load: allScenarios.load };
}

// =============================================================================
// Opções de execução
// =============================================================================
export const options = {
    scenarios: getScenarios(),
    thresholds: {
        kafka_produce_latency: ['p(95)<100', 'p(99)<200'],
        kafka_messages_failed: ['count<100'],
        success_rate: ['rate>0.99'],
    },
};

// =============================================================================
// Funções auxiliares
// =============================================================================

// Gera um UUID v4
function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// Gera string aleatória
function randomString(length) {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < length; i++) {
        result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
}

// Gera número aleatório entre min e max
function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// Gera mensagem no formato esperado pelo consumer
function generateMessage() {
    const now = new Date().toISOString();
    const messageId = generateUUID();
    
    return {
        message_id: messageId,
        event_type: 'LOAD_TEST',
        payload: JSON.stringify({
            orderId: `ORD-${messageId}`,
            customerId: `CUST-${randomInt(1000, 9999)}`,
            amount: Math.round(Math.random() * 990 * 100 + 1000) / 100, // 10.00 - 1000.00
            items: randomInt(1, 10),
        }),
        timestamp: now,
        source: 'k6-kafka-producer',
        correlation_id: `k6-corr-${randomString(6)}`,
    };
}

// Envia uma única mensagem para o Kafka
function sendMessage() {
    const message = generateMessage();
    const startTime = Date.now();
    
    try {
        writer.produce({
            messages: [
                {
                    key: stringToBytes(message.message_id),
                    value: stringToBytes(JSON.stringify(message)),
                },
            ],
        });

        const latency = Date.now() - startTime;
        
        messagesSent.add(1);
        kafkaLatency.add(latency);
        throughput.add(JSON.stringify(message).length);
        successRate.add(true);
        return true;
        
    } catch (e) {
        messagesFailedCounter.add(1);
        successRate.add(false);
        console.error(`Exception producing message: ${e}`);
        return false;
    }
}

// Envia múltiplas mensagens em batch
function sendBatch(count) {
    const messages = [];
    
    for (let i = 0; i < count; i++) {
        const message = generateMessage();
        messages.push({
            key: stringToBytes(message.message_id),
            value: stringToBytes(JSON.stringify(message)),
        });
    }
    
    const startTime = Date.now();
    
    try {
        writer.produce({ messages });
        const latency = Date.now() - startTime;
        
        messagesSent.add(count);
        kafkaLatency.add(latency / count); // Latência média por mensagem
        
        throughput.add(count * 200); // Estimativa de bytes
        
        successRate.add(true);
        return count;
        
    } catch (e) {
        messagesFailedCounter.add(count);
        successRate.add(false);
        console.error(`Exception producing batch: ${e}`);
        return 0;
    }
}

// Envia mensagem duplicada para teste de idempotência
function sendDuplicateMessage(messageId) {
    const now = new Date().toISOString();
    const message = {
        message_id: messageId,
        event_type: 'DUPLICATE_TEST',
        payload: JSON.stringify({
            orderId: `ORD-${messageId}`,
            customerId: `CUST-${randomInt(1000, 9999)}`,
            amount: Math.round(Math.random() * 990 * 100 + 1000) / 100,
            items: randomInt(1, 10),
        }),
        timestamp: now,
        source: 'k6-duplicate-test',
        correlation_id: `k6-dup-${messageId}`,
    };
    
    try {
        writer.produce({
            messages: [
                {
                    key: stringToBytes(message.message_id),
                    value: stringToBytes(JSON.stringify(message)),
                },
            ],
        });
        
        messagesSent.add(1);
        return true;
    } catch (e) {
        return false;
    }
}

// =============================================================================
// Cenários de teste
// =============================================================================

// Smoke test - verificação básica
export function smokeTest() {
    group('Smoke Test - Single Messages', () => {
        sendMessage();
        sleep(1);
    });
}

// Load test - carga normal
export function loadTest() {
    group('Load Test', () => {
        // 70% single messages, 30% batch
        if (Math.random() < 0.7) {
            sendMessage();
        } else {
            sendBatch(randomInt(10, 50));
        }
        sleep(randomInt(1, 3) / 10); // 0.1s - 0.3s
    });
}

// Stress test - carga alta
export function stressTest() {
    group('Stress Test', () => {
        // 50% single, 50% batch
        if (Math.random() < 0.5) {
            sendMessage();
        } else {
            sendBatch(randomInt(50, 200));
        }
        sleep(randomInt(1, 2) / 10); // 0.1s - 0.2s
    });
}

// Spike test - pico súbito
export function spikeTest() {
    group('Spike Test', () => {
        // Envio contínuo sem pausa
        sendMessage();
        if (Math.random() < 0.3) {
            sendBatch(randomInt(100, 500));
        }
    });
}

// Teste de throughput máximo
export function maxThroughputTest() {
    sendMessage();
}

// =============================================================================
// Função padrão
// =============================================================================
export default function () {
    loadTest();
}

// =============================================================================
// Setup e Teardown
// =============================================================================
export function setup() {
    console.log('='.repeat(60));
    console.log('K6 Kafka Load Test');
    console.log('='.repeat(60));
    console.log(`Kafka Brokers: ${KAFKA_BROKERS}`);
    console.log(`Topic: ${KAFKA_TOPIC}`);
    console.log(`Scenario: ${SCENARIO}`);
    console.log('='.repeat(60));
    
    // Testa conexão enviando mensagem de setup
    const testMessage = {
        message_id: `k6-setup-${generateUUID()}`,
        event_type: 'K6_SETUP',
        payload: '{"test": "connection"}',
        timestamp: new Date().toISOString(),
        source: 'k6-setup',
        correlation_id: 'setup',
    };
    
    try {
        writer.produce({
            messages: [{
                key: stringToBytes(testMessage.message_id),
                value: stringToBytes(JSON.stringify(testMessage)),
            }],
        });
        console.log('✓ Kafka connection successful');
    } catch (e) {
        console.error(`✗ Kafka connection failed: ${e}`);
    }
    
    return { 
        startTime: new Date().toISOString(),
        brokers: KAFKA_BROKERS,
        topic: KAFKA_TOPIC,
    };
}

export function teardown(data) {
    console.log('='.repeat(60));
    console.log('Test completed');
    console.log(`Started at: ${data.startTime}`);
    console.log(`Ended at: ${new Date().toISOString()}`);
    console.log('='.repeat(60));
    
    // Fecha o writer
    writer.close();
}
