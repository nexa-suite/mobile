#!/usr/bin/env python3
"""Exercise forbidden imports and accepted public application contracts."""
import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location(
    'buyer_architecture', Path(__file__).with_name('verify-context-architecture.py'))
architecture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(architecture)


class BuyerArchitectureTests(unittest.TestCase):
    def check(self, owner, source, expected):
        errors = architecture.inspect_sources({owner: source})
        if expected:
            self.assertTrue(any(expected in error for error in errors), errors)
        else:
            self.assertEqual(errors, [])

    def test_presentation_cannot_import_http_or_transport(self):
        owner = 'lib/contexts/sales_commitment/presentation/page.dart'
        self.check(owner, "import 'package:http/http.dart';", 'transport or device')
        self.check(owner, "import '../../../core/network/nexa_api_client.dart';", 'transport dependency')

    def test_application_cannot_export_implementation(self):
        self.check('lib/contexts/payments/application/port.dart',
                   "export '../infrastructure/stripe.dart';", 'application depends on implementation')

    def test_domain_remains_independent(self):
        owner = 'lib/contexts/payments/domain/balance.dart'
        self.check(owner, "import 'package:flutter/material.dart';", 'pure layer')
        self.check(owner, "import '../application/port.dart';", 'domain depends')
        self.check(owner, "import '../../credit_receivables/application/port.dart';", 'domain depends')

    def test_cross_context_implementation_is_private(self):
        self.check('lib/contexts/payments/infrastructure/adapter.dart',
                   "import '../../credit_receivables/infrastructure/repository.dart';", 'foreign context')

    def test_conditional_and_multiline_directives_are_checked(self):
        self.check('lib/contexts/payments/application/port.dart',
                   "import\n 'safe.dart' if (dart.library.io) 'dart:io';", 'pure layer')

    def test_foundation_cannot_export_context(self):
        self.check('lib/core/application/error/failure.dart',
                   "export 'package:nexa_buyer_mobile/contexts/payments/application/port.dart';", 'foundation depends')

    def test_comments_do_not_create_dependencies(self):
        self.check('lib/contexts/payments/application/port.dart',
                   "// import 'dart:io';\n/* export '../infrastructure/adapter.dart'; */", None)

    def test_ordinary_strings_do_not_create_dependencies(self):
        self.check('lib/contexts/payments/application/port.dart',
                   '''const example = 'import "dart:io";';''', None)

    def test_pure_layer_cannot_reach_ui_through_core(self):
        self.check('lib/contexts/payments/application/port.dart',
                   "import '../../../core/presentation/theme.dart';", 'implementation dependency')

    def test_allowed_application_contract_and_error_model(self):
        self.check('lib/contexts/payments/presentation/page.dart',
                   "import '../../tenant_access_governance/application/buyer_access_repository.dart';\n"
                   "import '../../../core/application/error/nexa_api_failure.dart';", None)
        self.check('lib/contexts/payments/infrastructure/adapter.dart',
                   "import '../../../core/network/nexa_api_client.dart';", None)

    def test_unknown_context_is_rejected(self):
        self.check('lib/contexts/mobile/domain/order.dart', '', 'unknown context')


if __name__ == '__main__':
    unittest.main()
