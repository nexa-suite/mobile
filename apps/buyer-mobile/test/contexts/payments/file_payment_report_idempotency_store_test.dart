import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/payments/infrastructure/file_payment_report_idempotency_store.dart';
import 'package:crypto/crypto.dart';

import 'dart:convert';

const _receivableId = '00000000-0000-4000-8000-000000000001';
const _scope = 'membership-1|tenant-1|workspace-1';

void main() {
  test(
    'persists scoped retry UUIDs without storing the bank reference',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'buyer-payment-retry-',
      );
      addTearDown(() => directory.delete(recursive: true));
      final fingerprint = _fingerprint('BANK-REF-71');
      final store = FilePaymentReportIdempotencyStore(
        directoryProvider: () async => directory,
      );

      final first = await store.keyFor(
        scopeKey: _scope,
        receivableId: _receivableId,
        referenceFingerprint: fingerprint,
      );
      final restored = FilePaymentReportIdempotencyStore(
        directoryProvider: () async => directory,
      );
      final retry = await restored.keyFor(
        scopeKey: _scope,
        receivableId: _receivableId,
        referenceFingerprint: fingerprint,
      );

      expect(first, matches(RegExp(r'^[0-9a-f-]{36}$')));
      expect(retry, first);
      expect(
        await restored.keyFor(
          scopeKey: 'other-membership|tenant-1|workspace-1',
          receivableId: _receivableId,
          referenceFingerprint: fingerprint,
        ),
        isNot(first),
      );
      expect(
        await restored.keyFor(
          scopeKey: _scope,
          receivableId: _receivableId,
          referenceFingerprint: _fingerprint('BANK-REF-72'),
        ),
        isNot(first),
      );

      final stored = await File(
        '${directory.path}/buyer-payment-report-retries.json',
      ).readAsString();
      expect(stored, isNot(contains('BANK-REF-71')));
      expect(stored, isNot(contains(_scope)));
      expect(stored, isNot(contains(_receivableId)));
      expect(stored, contains(first));

      final afterSuccess = await store.keyFor(
        scopeKey: _scope,
        receivableId: _receivableId,
        referenceFingerprint: fingerprint,
      );
      expect(afterSuccess, first);
    },
  );

  test(
    'corrupt retry metadata fails closed before a key is returned',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'buyer-payment-retry-corrupt-',
      );
      addTearDown(() => directory.delete(recursive: true));
      await File('${directory.path}/buyer-payment-report-retries.json')
          .writeAsString('{corrupt');
      final store = FilePaymentReportIdempotencyStore(
        directoryProvider: () async => directory,
      );

      await expectLater(
        store.keyFor(
          scopeKey: _scope,
          receivableId: _receivableId,
          referenceFingerprint: _fingerprint('BANK-REF-71'),
        ),
        throwsA(isA<FormatException>()),
      );
    },
  );
}

String _fingerprint(String reference) =>
    sha256.convert(utf8.encode(reference)).toString();
