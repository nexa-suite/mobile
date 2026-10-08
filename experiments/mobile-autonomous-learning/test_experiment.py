import json
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from run_experiment import (
    ALLOWED_NEXT_STEPS,
    DEFAULT_FIXTURE,
    GUIDANCE,
    load_fixture,
    run_case,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
SEARCH_STATE_SOURCE = (
    REPO_ROOT
    / "apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/warehouse/ProductSearchUiState.kt"
)
RESOURCE_FILES = [
    REPO_ROOT
    / "apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/res/values/context_strings.xml",
    REPO_ROOT
    / "apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/res/values-es/context_strings.xml",
]


class GuidanceExperimentTest(unittest.TestCase):
    def test_baseline_covers_current_product_search_status_enum(self):
        source = SEARCH_STATE_SOURCE.read_text(encoding="utf-8")
        enum_body = re.search(r"enum class ProductSearchStatus\s*\{([^}]*)\}", source)
        self.assertIsNotNone(enum_body)
        source_statuses = set(re.findall(r"\b[A-Z][A-Za-z0-9_]*\b", enum_body.group(1)))
        self.assertEqual(source_statuses, set(GUIDANCE))

    def test_fixture_covers_each_baseline_status_and_fault_classes(self):
        cases = load_fixture()
        normal_statuses = {
            case["status"] for case in cases if case["faultInjection"] == "NONE"
        }
        self.assertEqual(normal_statuses, set(GUIDANCE))
        faults = {case["faultInjection"] for case in cases}
        self.assertTrue({"ERRONEOUS_OUTPUT", "INCOMPLETE_OUTPUT", "UNAVAILABLE"} <= faults)

    def test_baseline_references_existing_copy_and_only_existing_actions(self):
        for path in RESOURCE_FILES:
            root = ET.parse(path).getroot()
            resource_keys = {
                element.attrib["name"]
                for element in root
                if element.tag in {"string", "plurals"} and "name" in element.attrib
            }
            for item in GUIDANCE.values():
                self.assertTrue(set(item["resource_keys"]) <= resource_keys)
                self.assertTrue(set(item["allowed_next_steps"]) <= ALLOWED_NEXT_STEPS)

    def test_fault_injections_keep_baseline_and_server_authority(self):
        for case in load_fixture():
            outcome = run_case(case)
            expected = GUIDANCE[case["status"]]
            self.assertEqual(outcome["guidance_topic"], expected["topic"])
            self.assertEqual(outcome["resource_keys"], expected["resource_keys"])
            self.assertEqual(outcome["allowed_next_steps"], expected["allowed_next_steps"])
            self.assertTrue(outcome["authority"]["server_confirmation_required"])
            self.assertFalse(outcome["authority"]["local_business_decision"])
            self.assertFalse(outcome["authority"]["inventory_mutation"])
            if case["faultInjection"] != "NONE":
                self.assertEqual(outcome["fault_handling"], "BASELINE_RETAINED")

    def test_runner_rejects_non_enum_or_extra_input(self):
        for case in (
            3,
            {"id": "unknown", "status": "LearnedProduct", "faultInjection": "NONE"},
            {
                "id": "has-query",
                "status": "Empty",
                "faultInjection": "NONE",
                "query": "synthetic but outside the schema",
            },
            {"id": "bad-fault", "status": "Empty", "faultInjection": "CONFIRM_LOCALLY"},
        ):
            with self.assertRaises(ValueError):
                run_case(case)

    def test_fixture_has_no_query_candidate_or_free_text_fields(self):
        document = json.loads(DEFAULT_FIXTURE.read_text(encoding="utf-8"))
        forbidden = {"query", "candidate", "tenant", "workspace", "text", "prompt"}
        for case in document["cases"]:
            self.assertEqual(set(case), {"id", "status", "faultInjection"})
            self.assertFalse(forbidden & set(case))


if __name__ == "__main__":
    unittest.main()
