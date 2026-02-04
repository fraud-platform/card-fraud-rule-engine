# Kafka Producer Optimization

**Purpose:** Document Kafka producer tuning for async decision event publishing.

**Last Updated:** 2026-02-02

---

## Problem Statement

Decision events are published asynchronously to Kafka. The default Quarkus/Kafka producer settings are not optimized for high-throughput scenarios.

---

## Optimization Applied

### Configuration Changes

| Setting | Default | Optimized | Benefit |
|----------|---------|-----------|---------|
| `batch.size` | 16384 (16KB) | 16384 (16KB) | Already optimal |
| `linger.ms` | 0 | 5 | Waits 5ms to batch more messages |
| `compression.type` | none | lz4 | LZ4 compression (fast, good ratio) |
| `acks` | all | 1 | Fire-and-forget (reliable delivery not critical for events) |

---

## Configuration Files Updated

### application.yaml

```yaml
mp:
  messaging:
    outgoing:
      decision-events:
        connector: smallrye-kafka
        topic: fraud.card.decisions.v1
        bootstrap.servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
        key.serializer: org.apache.kafka.common.serialization.StringSerializer
        value.serializer: org.apache.kafka.common.serialization.StringSerializer
        # Producer optimization
        batch.size: 16384
        linger.ms: 5
        compression.type: lz4
        acks: 1
```

### Profiles Updated
- `%test` - Test mode optimization
- `%load-test` - Load test mode optimization

---

## Trade-offs

| Setting | Trade-off | Decision |
|----------|-----------|----------|
| **linger.ms: 5** | +5ms latency for batch improvement | ✅ Worth it for improved throughput |
| **compression.type: lz4** | +CPU for bandwidth savings | ✅ LZ4 is fast, good compression ratio |
| **acks: 1** | Could lose events if broker crashes before ack | ✅ Acceptable for non-critical events |

---

## Performance Impact

### Before (Defaults)
- Messages sent immediately
- No compression
- Waiting for all replicas to ack
- Higher bandwidth usage

### After (Optimized)
- 5ms batching improves throughput
- LZ4 reduces bandwidth ~60-80%
- acks=1 reduces latency (single replica ack)
- Better throughput at the cost of minimal latency increase

---

## Expected Results

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Throughput | ~5K events/sec | ~8K events/sec | +60% |
| Bandwidth | ~100 KB/s | ~30 KB/s | -70% |
| Latency | 2ms | 7ms | +5ms (within SLO) |
| CPU | +0.1% | +0.4% | +0.3% |

---

## Monitoring

### Metrics to Watch

```bash
# Check producer metrics
curl http://localhost:8081/q/metrics | grep kafka
```

**Key Metrics:**
- `kafka.producer.record-send-rate` - Records sent per second
- `kafka.producer.record-error-rate` - Failed sends
- `kafka.producer.request-latency-avg` - Average request latency

---

## Validation

### Verify Configuration

```bash
# Start application with optimized settings
uv run doppler-local

# Check producer configuration via JMX or metrics
curl http://localhost:8081/q/metrics | grep -i kafka
```

---

## References

- [Quarkus Kafka Guide](https://quarkus.io/guides/kafka)
- [Kafka Producer Configs](https://kafka.apache.org/documentation/#producerconfigs)

---

**End of Document**
