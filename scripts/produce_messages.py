#!/usr/bin/env python3
"""
Script para produzir mensagens de teste para o consumidor Kafka.
"""

import json
import time
import random
from datetime import datetime

try:
    from kafka import KafkaProducer
    from kafka.errors import KafkaError
except ImportError:
    print("ERROR: kafka-python not installed!")
    print("Install with: pip install kafka-python")
    exit(1)

KAFKA_BOOTSTRAP_SERVERS = ['localhost:9092']
TOPIC_NAME = 'high-volume-topic'

EVENT_TYPES = [
    'ORDER_CREATED',
    'PAYMENT_RECEIVED',
    'INVENTORY_UPDATE',
    'ORDER_CANCELLED',
    'SHIPMENT_CREATED'
]

def create_producer():
    return KafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8') if k else None,
        acks='all',
        retries=3,
        max_in_flight_requests_per_connection=5,
        compression_type='gzip'
    )

def generate_message(message_id: str, event_type: str = None) -> dict:
    if event_type is None:
        event_type = random.choice(EVENT_TYPES)
    
    if event_type == 'ORDER_CREATED':
        payload = {
            'orderId': f'ORD-{message_id}',
            'customerId': f'CUST-{random.randint(1000, 9999)}',
            'amount': round(random.uniform(10.0, 1000.0), 2),
            'items': random.randint(1, 10)
        }
    elif event_type == 'PAYMENT_RECEIVED':
        payload = {
            'paymentId': f'PAY-{message_id}',
            'orderId': f'ORD-{random.randint(1000, 9999)}',
            'amount': round(random.uniform(10.0, 1000.0), 2),
            'method': random.choice(['CREDIT_CARD', 'PIX', 'BOLETO'])
        }
    elif event_type == 'INVENTORY_UPDATE':
        payload = {
            'productId': f'PROD-{random.randint(100, 999)}',
            'quantity': random.randint(-50, 100),
            'warehouse': random.choice(['WH1', 'WH2', 'WH3'])
        }
    else:
        payload = {
            'id': message_id,
            'data': f'Sample data for {event_type}'
        }
    
    return {
        'message_id': message_id,
        'event_type': event_type,
        'payload': json.dumps(payload),
        'timestamp': datetime.now().isoformat(),
        'source': 'load-test-script',
        'correlation_id': f'corr-{message_id}'
    }

def send_single_message(producer, message_id: str, event_type: str = None):
    message = generate_message(message_id, event_type)
    
    try:
        future = producer.send(
            TOPIC_NAME,
            key=message['message_id'],
            value=message
        )
        
        record_metadata = future.get(timeout=10)
        
        print(f"✓ Sent: {message['message_id']} | "
              f"Type: {message['event_type']} | "
              f"Partition: {record_metadata.partition} | "
              f"Offset: {record_metadata.offset}")
        
        return True
        
    except KafkaError as e:
        print(f"✗ Error sending {message['message_id']}: {e}")
        return False

def load_test(num_messages: int, messages_per_second: int = 100, 
              duplicate_percentage: float = 0.1):
    producer = create_producer()
    
    print(f"\n{'='*60}")
    print(f"KAFKA LOAD TEST")
    print(f"{'='*60}")
    print(f"Target: {num_messages:,} messages")
    print(f"Rate: {messages_per_second} msgs/s")
    print(f"Duplicates: {duplicate_percentage*100:.1f}%")
    print(f"Topic: {TOPIC_NAME}")
    print(f"{'='*60}\n")
    
    sent = 0
    errors = 0
    duplicates = 0
    start_time = time.time()
    
    sent_ids = []
    
    try:
        for i in range(num_messages):
            if sent_ids and random.random() < duplicate_percentage:
                message_id = random.choice(sent_ids)
                duplicates += 1
                print(f"⚠ Sending duplicate: {message_id}")
            else:
                message_id = f"msg-{i:08d}"
                sent_ids.append(message_id)
            
            if send_single_message(producer, message_id):
                sent += 1
            else:
                errors += 1
            
            elapsed = time.time() - start_time
            expected_sent = int(elapsed * messages_per_second)
            
            if sent > expected_sent:
                sleep_time = (sent - expected_sent) / messages_per_second
                time.sleep(sleep_time)
            
            if (i + 1) % 1000 == 0:
                elapsed = time.time() - start_time
                rate = sent / elapsed if elapsed > 0 else 0
                print(f"\n--- Progress: {i+1}/{num_messages} | "
                      f"Rate: {rate:.1f} msgs/s | "
                      f"Elapsed: {elapsed:.1f}s ---\n")
        
        producer.flush()
        
    finally:
        producer.close()
    
    total_time = time.time() - start_time
    avg_rate = sent / total_time if total_time > 0 else 0
    
    print(f"\n{'='*60}")
    print(f"RESULTS")
    print(f"{'='*60}")
    print(f"Total sent: {sent:,}")
    print(f"Duplicates sent: {duplicates:,}")
    print(f"Errors: {errors:,}")
    print(f"Time: {total_time:.2f}s")
    print(f"Average rate: {avg_rate:.1f} msgs/s")
    print(f"{'='*60}\n")

def send_test_suite():
    producer = create_producer()
    
    print("\n" + "="*60)
    print("SENDING TEST SUITE")
    print("="*60 + "\n")
    
    test_cases = [
        ("msg-test-001", "ORDER_CREATED"),
        ("msg-test-002", "PAYMENT_RECEIVED"),
        ("msg-test-003", "INVENTORY_UPDATE"),
        ("msg-test-001", "ORDER_CREATED"),  # Duplicata!
        ("msg-test-004", "ORDER_CANCELLED"),
        ("msg-test-005", "SHIPMENT_CREATED"),
    ]
    
    for message_id, event_type in test_cases:
        send_single_message(producer, message_id, event_type)
        time.sleep(0.5)
    
    producer.close()
    print("\n✓ Test suite completed\n")

def main():
    print("\n" + "="*60)
    print("KAFKA MESSAGE PRODUCER")
    print("="*60)
    print("\nOptions:")
    print("1. Send test suite (6 messages)")
    print("2. Load test - 1,000 messages")
    print("3. Load test - 10,000 messages")
    print("4. Load test - 100,000 messages")
    print("5. Custom load test")
    print("0. Exit")
    
    choice = input("\nSelect option: ").strip()
    
    if choice == "1":
        send_test_suite()
    elif choice == "2":
        load_test(1000, messages_per_second=100, duplicate_percentage=0.1)
    elif choice == "3":
        load_test(10000, messages_per_second=200, duplicate_percentage=0.1)
    elif choice == "4":
        load_test(100000, messages_per_second=500, duplicate_percentage=0.1)
    elif choice == "5":
        try:
            num = int(input("Number of messages: "))
            rate = int(input("Messages per second: "))
            dup = float(input("Duplicate percentage (0-1): "))
            load_test(num, messages_per_second=rate, duplicate_percentage=dup)
        except ValueError:
            print("Invalid input!")
    elif choice == "0":
        print("Goodbye!")
    else:
        print("Invalid option!")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nInterrupted by user. Exiting...")
    except Exception as e:
        print(f"\nError: {e}")
