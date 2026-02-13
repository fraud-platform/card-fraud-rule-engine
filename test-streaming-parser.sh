#!/bin/bash
#
# Streaming Parser Load Test
# Measures impact of streaming JSON parser vs standard Jackson
#
# Usage: ./test-streaming-parser.sh
#

set -e

REPO_ROOT="/c/Users/kanna/github/card-fraud-rule-engine"
E2E_REPO="/c/Users/kanna/github/card-fraud-e2e-load-testing"
JAR_PATH="$REPO_ROOT/target/card-fraud-rule-engine-1.0.0-SNAPSHOT-runner.jar"

# Colors
GREEN='\033[0.32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "Streaming Parser Load Test"
echo "=========================================="
echo ""

# Check JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo -e "${RED}ERROR: JAR not found at $JAR_PATH${NC}"
    echo "Run: mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar"
    exit 1
fi

# Check e2e repo exists
if [ ! -d "$E2E_REPO" ]; then
    echo -e "${RED}ERROR: e2e repo not found at $E2E_REPO${NC}"
    exit 1
fi

echo -e "${GREEN}✓ JAR found: $(ls -lh $JAR_PATH | awk '{print $5}')${NC}"
echo ""

# Start JAR with JFR profiling
echo "Starting rule engine with JFR profiling..."
cd "$REPO_ROOT"

# Kill any existing Java processes
pkill -f card-fraud-rule-engine || true
sleep 2

# Start JAR in background with JFR
QUARKUS_SMALLRYE_JWT_ENABLED=false \
RULESET_STARTUP_LOAD_ENABLED=true \
RULESET_STARTUP_FAIL_FAST=false \
LOAD_SHEDDING_ENABLED=false \
doppler run --config local -- \
  java \
  -XX:+FlightRecorder \
  -XX:StartFlightRecording=filename=/tmp/streaming-parser-test.jfr,dumponexit=true,settings=profile \
  -XX:+AlwaysPreTouch \
  -Xms1G \
  -Xmx2G \
  -jar "$JAR_PATH" > /tmp/rule-engine.log 2>&1 &

JAVA_PID=$!
echo -e "${GREEN}✓ Java process started: PID=$JAVA_PID${NC}"

# Wait for health check
echo "Waiting for service to be healthy..."
MAX_WAIT=60
COUNTER=0
while [ $COUNTER -lt $MAX_WAIT ]; do
    if curl -s http://localhost:8081/q/health/ready | grep -q "UP"; then
        echo -e "${GREEN}✓ Service is healthy${NC}"
        break
    fi
    sleep 1
    COUNTER=$((COUNTER+1))
    echo -n "."
done

if [ $COUNTER -eq $MAX_WAIT ]; then
    echo -e "${RED}ERROR: Service failed to start${NC}"
    cat /tmp/rule-engine.log
    kill $JAVA_PID
    exit 1
fi

echo ""
echo "Waiting additional 10s for JIT warmup..."
sleep 10

# Load rulesets if needed
echo "Loading rulesets via bulk-load..."
cd "$REPO_ROOT"
curl -X POST http://localhost:8081/v1/evaluate/rulesets/bulk-load \
  -H "Content-Type: application/json" \
  -d @bulk-load-request.json || true

echo ""
echo "Waiting 5s for rulesets to be loaded..."
sleep 5

# Run load test
echo ""
echo "=========================================="
echo "Running Load Test (2 min, 100 users)"
echo "=========================================="
cd "$E2E_REPO"

uv run lt-run \
  --service rule-engine \
  --users=100 \
  --spawn-rate=20 \
  --run-time=2m \
  --scenario baseline \
  --auth-mode none \
  --headless

# Get latest results
LATEST_SUMMARY=$(ls -t html-reports/run-summary*.json | head -1)
echo ""
echo "=========================================="
echo "Load Test Results"
echo "=========================================="
cat "$LATEST_SUMMARY" | python -m json.tool | grep -E "total_requests|total_failures|avg_response_time|p50|p95|p99|rps"

# Stop JAR
echo ""
echo "Stopping rule engine..."
kill $JAVA_PID
wait $JAVA_PID 2>/dev/null || true

echo ""
echo -e "${GREEN}✓ Test complete!${NC}"
echo ""
echo "Results:"
echo "  - Load test summary: $LATEST_SUMMARY"
echo "  - JFR recording: /tmp/streaming-parser-test.jfr"
echo "  - Engine log: /tmp/rule-engine.log"
echo ""
echo "To analyze JFR:"
echo "  jmc /tmp/streaming-parser-test.jfr"
echo ""
