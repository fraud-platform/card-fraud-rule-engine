"""
E2E Tests for MONITORING Evaluation Endpoint

Works in both no-auth and JWT modes.
"""

import pytest

from conftest import _is_jwt_mode


class TestMONITORINGEvaluation:
    """Test MONITORING evaluation endpoint."""

    def test_MONITORING_preserves_approve_decision(self, client, test_transaction_id, test_card_hash):
        """Test MONITORING preserves APPROVE decision."""
        payload = {
            "transaction_id": test_transaction_id,
            "card_hash": test_card_hash,
            "amount": 100.00,
            "currency": "USD",
            "transaction_type": "PURCHASE",
            "decision": "APPROVE",
        }

        response = client.post("/v1/evaluate/monitoring", json=payload)
        assert response.status_code == 200

        data = response.json()
        assert data["decision"] == "APPROVE"
        assert data["transaction_id"] == test_transaction_id
        assert data["evaluation_type"] == "MONITORING"
        assert "decision_id" in data

    def test_MONITORING_with_decline_decision(self, client, test_transaction_id, test_card_hash):
        """Test MONITORING handles DECLINE decision input."""
        payload = {
            "transaction_id": test_transaction_id,
            "card_hash": test_card_hash,
            "amount": 600.00,
            "currency": "USD",
            "transaction_type": "PURCHASE",
            "decision": "DECLINE",
        }

        response = client.post("/v1/evaluate/monitoring", json=payload)
        assert response.status_code == 200

        data = response.json()
        # In FAIL_OPEN mode (no rulesets loaded), engine returns APPROVE
        # With rulesets loaded, may preserve DECLINE
        assert data["decision"] in ["APPROVE", "DECLINE"]
        assert data["transaction_id"] == test_transaction_id

    def test_MONITORING_missing_decision_400(self, client, test_transaction_id, test_card_hash):
        """Test MONITORING without decision field returns 400."""
        payload = {
            "transaction_id": test_transaction_id,
            "card_hash": test_card_hash,
            "amount": 100.00,
            "currency": "USD",
            "transaction_type": "PURCHASE",
        }

        response = client.post("/v1/evaluate/monitoring", json=payload)
        assert response.status_code == 400

    def test_MONITORING_invalid_decision_400(self, client, test_transaction_id, test_card_hash):
        """Test MONITORING with invalid decision returns 400."""
        payload = {
            "transaction_id": test_transaction_id,
            "card_hash": test_card_hash,
            "amount": 100.00,
            "currency": "USD",
            "transaction_type": "PURCHASE",
            "decision": "INVALID_DECISION",
        }

        response = client.post("/v1/evaluate/monitoring", json=payload)
        assert response.status_code == 400

    def test_MONITORING_has_matched_rules(self, client, test_transaction_id, test_card_hash):
        """Test MONITORING response includes matched_rules."""
        payload = {
            "transaction_id": test_transaction_id,
            "card_hash": test_card_hash,
            "amount": 600.00,
            "currency": "USD",
            "transaction_type": "PURCHASE",
            "decision": "APPROVE",
        }

        response = client.post("/v1/evaluate/monitoring", json=payload)
        assert response.status_code == 200

        data = response.json()
        assert data["decision"] == "APPROVE"
        assert "matched_rules" in data

    @pytest.mark.skipif(not _is_jwt_mode(), reason="Auth rejection only testable in JWT mode")
    def test_MONITORING_without_auth_rejected(self, unauth_client, test_transaction_id, test_card_hash):
        """Test MONITORING without authentication returns 401/403."""
        payload = {
            "transaction_id": test_transaction_id,
            "card_hash": test_card_hash,
            "amount": 100.00,
            "currency": "USD",
            "decision": "APPROVE",
        }

        response = unauth_client.post("/v1/evaluate/monitoring", json=payload)
        assert response.status_code in [401, 403]
