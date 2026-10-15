import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/file_buyer_receipt_evidence_idempotency_store.dart';

const _receiptId = '00000000-0000-4000-8000-000000000030';
const _firstFingerprint =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const _secondFingerprint =
    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

void main() {
  late Directory directory;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp(
      'buyer-receipt-evidence-keys-',
    );
  });

  tearDown(() async {
    if (await directory.exists()) await directory.delete(recursive: true);
  });

  test('persists one retry key per scope, receipt and payload', () async {
    final firstStore = FileBuyerReceiptEvidenceIdempotencyStore(
      directoryProvider: () async => directory,
    );
    final firstKey = await firstStore.keyFor(
      scopeKey: 'membership|tenant|workspace',
      receiptId: _receiptId,
      payloadFingerprint: _firstFingerprint,
    );
    final secondStore = FileBuyerReceiptEvidenceIdempotencyStore(
      directoryProvider: () async => directory,
    );

    expect(
      await secondStore.keyFor(
        scopeKey: 'membership|tenant|workspace',
        receiptId: _receiptId,
        payloadFingerprint: _firstFingerprint,
      ),
      firstKey,
    );
    final secondAttachment = await secondStore.keyFor(
      scopeKey: 'membership|tenant|workspace',
      receiptId: _receiptId,
      payloadFingerprint: _secondFingerprint,
    );
    final otherScope = await secondStore.keyFor(
      scopeKey: 'another-membership|tenant|workspace',
      receiptId: _receiptId,
      payloadFingerprint: _firstFingerprint,
    );

    expect(secondAttachment, isNot(firstKey));
    expect(otherScope, isNot(firstKey));
    final persisted = await File(
      '${directory.path}/buyer-receipt-evidence-retries.json',
    ).readAsString();
    expect(persisted, contains(_firstFingerprint));
    expect(persisted, contains(_secondFingerprint));
    expect(persisted, isNot(contains('membership|tenant|workspace')));
    expect(persisted, isNot(contains('filename')));
    expect(persisted, isNot(contains('bytes')));
  });
}
