#!/usr/bin/env python3
"""Run a deterministic, enum-only guidance baseline for manual SKU search."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


EXPERIMENT_DIR = Path(__file__).resolve().parent
DEFAULT_FIXTURE = EXPERIMENT_DIR / "fixtures" / "manual-sku-guidance.json"

# Snapshot of current ProductSearchStatus values in the Android presentation
# source. The unit test checks this set against that source file.
GUIDANCE: dict[str, dict[str, Any]] = {
    "Initial": {
        "topic": "manual_search_start",
        "resource_keys": ["warehouse_search_helper", "warehouse_search_initial_body"],
        "allowed_next_steps": ["EDIT_QUERY", "SEARCH"],
    },
    "Typing": {
        "topic": "manual_search_start",
        "resource_keys": ["warehouse_search_helper"],
        "allowed_next_steps": ["SEARCH"],
    },
    "InvalidQuery": {
        "topic": "correct_search_input",
        "resource_keys": ["warehouse_search_invalid"],
        "allowed_next_steps": ["EDIT_QUERY"],
    },
    "Loading": {
        "topic": "search_in_progress",
        "resource_keys": ["warehouse_search_loading_body"],
        "allowed_next_steps": [],
    },
    "OneCandidate": {
        "topic": "candidate_requires_server_confirmation",
        "resource_keys": ["warehouse_results_title"],
        "allowed_next_steps": ["SELECT_DISPLAYED_CANDIDATE"],
    },
    "MultipleCandidates": {
        "topic": "review_candidates_before_server_confirmation",
        "resource_keys": ["warehouse_results_title"],
        "allowed_next_steps": ["SELECT_DISPLAYED_CANDIDATE"],
    },
    "Empty": {
        "topic": "adjust_manual_search",
        "resource_keys": ["warehouse_search_empty_body"],
        "allowed_next_steps": ["EDIT_QUERY", "SEARCH"],
    },
    "LoadingMore": {
        "topic": "more_results_in_progress",
        "resource_keys": ["warehouse_results_title"],
        "allowed_next_steps": [],
    },
    "LoadMoreFailed": {
        "topic": "retry_loading_results",
        "resource_keys": ["warehouse_load_more_error"],
        "allowed_next_steps": ["RETRY_LOAD_MORE"],
    },
    "ConfirmationPending": {
        "topic": "server_confirmation_in_progress",
        "resource_keys": ["warehouse_confirmation_pending_body"],
        "allowed_next_steps": [],
    },
    "CandidateUnavailable": {
        "topic": "candidate_must_be_rechecked",
        "resource_keys": ["warehouse_candidate_unavailable_body"],
        "allowed_next_steps": ["SEARCH"],
    },
    "NetworkUnavailable": {
        "topic": "network_failure_no_offline_decision",
        "resource_keys": ["warehouse_network_error"],
        "allowed_next_steps": ["SEARCH_AGAIN"],
    },
    "ServiceUnavailable": {
        "topic": "service_failure_no_local_decision",
        "resource_keys": ["warehouse_service_error"],
        "allowed_next_steps": ["SEARCH_AGAIN"],
    },
    "PermissionDenied": {
        "topic": "authorization_boundary",
        "resource_keys": ["warehouse_permission_error"],
        "allowed_next_steps": [],
    },
    "ContextInvalidated": {
        "topic": "refresh_authorized_context",
        "resource_keys": ["warehouse_context_error"],
        "allowed_next_steps": ["CHANGE_CONTEXT"],
    },
    "SessionInvalidated": {
        "topic": "session_recovery_required",
        "resource_keys": ["warehouse_session_error"],
        "allowed_next_steps": [],
    },
    "IntegrationUnavailable": {
        "topic": "integration_unavailable_no_local_decision",
        "resource_keys": ["warehouse_integration_error"],
        "allowed_next_steps": ["SEARCH_AGAIN"],
    },
}

# These actions are already exposed by the manual SKU search surface. The
# tests verify resource identifiers; source locations are documented in README.
ALLOWED_NEXT_STEPS = {
    "SEARCH",
    "EDIT_QUERY",
    "SELECT_DISPLAYED_CANDIDATE",
    "RETRY_LOAD_MORE",
    "SEARCH_AGAIN",
    "CHANGE_CONTEXT",
}
FAULT_INJECTIONS = {"NONE", "ERRONEOUS_OUTPUT", "INCOMPLETE_OUTPUT", "UNAVAILABLE"}
CASE_KEYS = {"id", "status", "faultInjection"}


def run_case(case: dict[str, Any]) -> dict[str, Any]:
    """Return baseline tokens only; fault labels never supply guidance."""
    if not isinstance(case, dict):
        raise ValueError("each case must be an object")
    if set(case) != CASE_KEYS:
        raise ValueError("case must contain only id, status, and faultInjection")
    case_id = case["id"]
    status = case["status"]
    fault = case["faultInjection"]
    if not isinstance(case_id, str) or not case_id:
        raise ValueError("case id must be a non-empty string")
    if not isinstance(status, str) or status not in GUIDANCE:
        raise ValueError(f"unknown ProductSearchStatus: {status!r}")
    if not isinstance(fault, str) or fault not in FAULT_INJECTIONS:
        raise ValueError(f"unknown fault injection: {fault!r}")

    baseline = GUIDANCE[status]
    return {
        "case_id": case_id,
        "input_status": status,
        "guidance_topic": baseline["topic"],
        "resource_keys": list(baseline["resource_keys"]),
        "allowed_next_steps": list(baseline["allowed_next_steps"]),
        "source": "deterministic_existing_resource_baseline",
        "fault_injection": fault,
        "fault_handling": (
            "BASELINE_RETAINED" if fault != "NONE" else "NO_FAULT_INJECTED"
        ),
        "authority": {
            "server_confirmation_required": True,
            "local_business_decision": False,
            "inventory_mutation": False,
        },
    }


def load_fixture(path: Path = DEFAULT_FIXTURE) -> list[dict[str, Any]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError("fixture document must be an object")
    if set(document) != {"schemaVersion", "cases"} or document["schemaVersion"] != 1:
        raise ValueError("fixture must use schemaVersion 1 and contain only cases")
    if not isinstance(document["cases"], list):
        raise ValueError("fixture cases must be an array")
    return document["cases"]


def main() -> int:
    try:
        outcomes = [run_case(case) for case in load_fixture()]
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    print(json.dumps({"case_count": len(outcomes), "cases": outcomes}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
