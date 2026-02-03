"""
Locust load testing for Card Fraud Rule Engine.

Supports two modes:
  1. JWT mode (default): Fetches ONE Auth0 token and shares across all users
  2. No-auth mode: Set NO_AUTH=true for testing against dev server (JWT disabled)

Usage:
  # JWT mode (server with JWT enabled)
  uv run doppler-load-test                  # Start server (terminal 1)
  doppler run --project card-fraud-rule-engine --config=local -- \\
    locust -f load-testing/locustfile.py --host=http://localhost:8081

  # No-auth mode (dev server, no JWT)
  uv run doppler-local                      # Start server (terminal 1)
  NO_AUTH=true locust -f load-testing/locustfile.py --host=http://localhost:8081

  # Headless mode
  locust -f load-testing/locustfile.py --host=http://localhost:8081 \\
    --headless -u 50 -r 5 --run-time=2m

  # Docker Compose (all-in-one, JWT mode)
  doppler run --project card-fraud-rule-engine --config=local -- \\
    docker compose --profile load-testing up

Targets:
  - P95 latency < 15ms for AUTH
  - P99 latency < 30ms
  - 10,000+ TPS sustained
"""

import json
import uuid
import random
import os
import sys
import time
from locust import HttpUser, task, between, constant, events
import urllib.request
import urllib.error

# ========== Configuration ==========

NO_AUTH = os.environ.get("NO_AUTH", "false").lower() in ("true", "1", "yes")

# Auth0 config (from Doppler env vars)
AUTH0_DOMAIN = os.environ.get("AUTH0_DOMAIN", "")
AUTH0_CLIENT_ID = os.environ.get("AUTH0_CLIENT_ID", "")
AUTH0_CLIENT_SECRET = os.environ.get("AUTH0_CLIENT_SECRET", "")
AUTH0_AUDIENCE = os.environ.get("AUTH0_AUDIENCE", "")
TARGET_HOST = os.environ.get("TARGET_HOST", "http://localhost:8081")

# Test data pools
CURRENCIES = ["USD", "GBP", "EUR", "CAD", "AUD", "JPY"]
COUNTRIES = ["US", "GB", "CA", "DE", "FR", "AU", "JP", "IN", "BR", "MX"]
MERCHANT_CATEGORIES = ["5411", "5999", "5734", "5812", "5541", "5942", "5912"]
TRANSACTION_TYPES = ["PURCHASE", "REFUND", "AUTHORIZATION"]
ENTRY_MODES = ["ECOM", "CHIP", "MAGSTRIPE", "CONTACTLESS", "MANUAL"]
CARD_HASHES = [f"card_hash_{i:04d}" for i in range(100)]

# ========== Module-level token cache ==========
# Fetched ONCE and shared by all Locust users (minimizes Auth0 API calls)
_cached_token: str | None = None


def _get_shared_token() -> str | None:
    """Get the shared Auth0 token, fetching it once if needed."""
    global _cached_token

    if NO_AUTH:
        return None

    if _cached_token is not None:
        return _cached_token

    if not all([AUTH0_DOMAIN, AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET, AUTH0_AUDIENCE]):
        print("WARNING: Auth0 env vars not set and NO_AUTH is not true.")
        print("  Set NO_AUTH=true for no-auth mode, or provide Auth0 credentials.")
        print("  Required: AUTH0_DOMAIN, AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET, AUTH0_AUDIENCE")
        sys.exit(1)

    url = f"https://{AUTH0_DOMAIN}/oauth/token"
    payload = {
        "grant_type": "client_credentials",
        "client_id": AUTH0_CLIENT_ID,
        "client_secret": AUTH0_CLIENT_SECRET,
        "audience": AUTH0_AUDIENCE,
    }

    try:
        data = json.dumps(payload).encode()
        req = urllib.request.Request(
            url, data=data, headers={"Content-Type": "application/json"}, method="POST"
        )
        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.loads(response.read().decode())
            token = result.get("access_token")
            if token:
                expires = result.get("expires_in", "unknown")
                print(f"Auth0 token cached (expires in {expires}s) - shared by all users")
                _cached_token = token
                return token
            else:
                print("ERROR: No access_token in Auth0 response")
                sys.exit(1)
    except urllib.error.HTTPError as e:
        print(f"ERROR: Auth0 token fetch failed: HTTP {e.code}")
        print(f"Response: {e.read().decode()}")
        sys.exit(1)
    except Exception as e:
        print(f"ERROR: Auth0 token fetch failed: {e}")
        sys.exit(1)


# ========== Request Generators ==========

def generate_transaction_id():
    return f"txn-{uuid.uuid4().hex[:16]}"


def generate_card_bin():
    visa_bins = ["411111", "400000", "450000"]
    mc_bins = ["555555", "520000", "540000"]
    amex_bins = ["340000", "370000"]
    return random.choice(visa_bins + mc_bins + amex_bins)


def generate_AUTH_request():
    card_bin = generate_card_bin()
    network = (
        "VISA" if card_bin.startswith("4")
        else ("MASTERCARD" if card_bin.startswith("5") else "AMEX")
    )
    return {
        "transaction_id": generate_transaction_id(),
        "card_hash": random.choice(CARD_HASHES),
        "amount": round(random.uniform(10.0, 2000.0), 2),
        "currency": random.choice(CURRENCIES),
        "country_code": random.choice(COUNTRIES),
        "merchant_id": f"merch_{random.randint(1000, 9999)}",
        "merchant_name": f"Test Merchant {random.randint(1, 100)}",
        "merchant_category": random.choice(MERCHANT_CATEGORIES),
        "merchant_category_code": random.choice(MERCHANT_CATEGORIES),
        "card_present": random.choice([True, False]),
        "transaction_type": random.choice(TRANSACTION_TYPES),
        "entry_mode": random.choice(ENTRY_MODES),
        "ip_address": f"192.168.{random.randint(0, 255)}.{random.randint(0, 255)}",
        "device_id": f"device_{random.randint(1000, 9999)}",
        "card_network": network,
        "card_bin": card_bin,
    }


def generate_MONITORING_request():
    return {
        "transaction_id": generate_transaction_id(),
        "card_hash": random.choice(CARD_HASHES),
        "amount": round(random.uniform(10.0, 500.0), 2),
        "currency": random.choice(CURRENCIES),
        "country_code": random.choice(COUNTRIES),
        "merchant_id": f"merch_{random.randint(1000, 9999)}",
        "merchant_category_code": random.choice(MERCHANT_CATEGORIES),
        "decision": random.choice(["APPROVE", "DECLINE"]),
    }


def generate_velocity_burst_request():
    req = generate_AUTH_request()
    req["card_hash"] = "velocity_test_card"
    req["amount"] = round(random.uniform(50.0, 200.0), 2)
    return req


# ========== Locust User Classes ==========

class CardFraudUser(HttpUser):
    """Standard user: 70% AUTH, 30% MONITORING."""

    wait_time = between(10, 50)  # milliseconds

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.headers = self._get_headers()

    def on_start(self):
        self.client.verify = False

    def _get_headers(self):
        """Get request headers with optional auth token."""
        headers = {"Content-Type": "application/json"}
        token = _get_shared_token()
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return headers

    @task(7)
    def AUTH_evaluation(self):
        with self.client.post(
            "/v1/evaluate/auth",
            json=generate_AUTH_request(),
            headers=self.headers,
            catch_response=True,
            name="/v1/evaluate/auth",
        ) as response:
            self._handle_response(response, "AUTH")

    @task(3)
    def MONITORING_evaluation(self):
        with self.client.post(
            "/v1/evaluate/monitoring",
            json=generate_MONITORING_request(),
            headers=self.headers,
            catch_response=True,
            name="/v1/evaluate/monitoring",
        ) as response:
            self._handle_response(response, "MONITORING")

    def _handle_response(self, response, eval_type):
        if response.status_code == 200:
            try:
                data = response.json()
                mode = data.get("engine_mode", "NORMAL")
                if mode in ("FAIL_OPEN", "DEGRADED"):
                    error_code = data.get("engine_error_code", "UNKNOWN")
                    response.failure(f"{mode}: {error_code}")
                else:
                    response.success()
            except json.JSONDecodeError:
                response.failure("Invalid JSON response")
        elif response.status_code == 401:
            response.failure("Auth failed - check JWT_TOKEN or use NO_AUTH=true")
        elif response.status_code == 403:
            response.failure("Forbidden - check scopes")
        else:
            response.failure(f"Status {response.status_code}: {response.text[:100]}")


class HighVolumeUser(CardFraudUser):
    """High-volume stress testing user."""
    wait_time = between(1, 5)


class VelocityTestUser(CardFraudUser):
    """Velocity limit testing user."""
    wait_time = between(100, 200)

    @task
    def velocity_burst(self):
        with self.client.post(
            "/v1/evaluate/auth",
            json=generate_velocity_burst_request(),
            headers=self.headers,
            catch_response=True,
            name="/v1/evaluate/auth [velocity]",
        ) as response:
            self._handle_response(response, "VELOCITY")


class SteadyStateUser(HttpUser):
    """Steady-state baseline measurement user."""
    wait_time = constant(0.1)

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.headers = self._get_headers()

    def _get_headers(self):
        """Get request headers with optional auth token."""
        headers = {"Content-Type": "application/json"}
        token = _get_shared_token()
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return headers

    @task
    def AUTH_only(self):
        self.client.post(
            "/v1/evaluate/auth",
            json=generate_AUTH_request(),
            headers=self.headers,
        )


# ========== Event Handlers ==========

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    print("=" * 60)
    print("Card Fraud Rule Engine Load Test")
    print(f"  Mode: {'NO-AUTH' if NO_AUTH else 'JWT'}")
    print(f"  Target: {environment.host}")
    if not NO_AUTH:
        domain_display = f"{AUTH0_DOMAIN[:20]}..." if AUTH0_DOMAIN else "Not configured"
        print(f"  Auth0: {domain_display}")
    print("=" * 60)

    # Pre-fetch the shared token (if JWT mode)
    if not NO_AUTH:
        _get_shared_token()

    # Load rulesets before starting
    try:
        headers = {"Content-Type": "application/json"}
        token = _get_shared_token()
        if token:
            headers["Authorization"] = f"Bearer {token}"

        bulk_load_payload = {
            "rulesets": [
                {"key": "CARD_AUTH", "version": 1, "country": "global"},
                {"key": "CARD_MONITORING", "version": 1, "country": "global"},
            ]
        }

        url = f"{TARGET_HOST}/v1/evaluate/rulesets/bulk-load"
        data = json.dumps(bulk_load_payload).encode()
        req = urllib.request.Request(url, data=data, headers=headers, method="POST")

        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.loads(response.read().decode())
            print(f"  Rulesets loaded: {result.get('loaded', 0)} / {result.get('requested', 0)}")
    except Exception as e:
        print(f"  Warning: Could not load rulesets: {e}")
        print("  Load test will proceed with existing rulesets (if any)")

    print("=" * 60)


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("=" * 60)
    print("Load Test Complete")
    print("=" * 60)


@events.request.add_listener
def on_request(request_type, name, response_time, response_length, exception, context, **kwargs):
    if response_time > 50:
        print(f"SLOW: {name} {response_time:.0f}ms")
