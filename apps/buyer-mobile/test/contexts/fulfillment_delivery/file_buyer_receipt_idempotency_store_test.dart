import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_receipt_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/file_buyer_receipt_idempotency_store.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000001';
const _attemptId = '00000000-0000-4000-8000-000000000002';
const _fingerprint =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const _changedFingerprint =
    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

void main() {
  late Directory directory;
  late FileBuyerReceiptIdempotencyStore store;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('buyer-receipt-store-');
    store = FileBuyerReceiptIdempotencyStore(
      directoryProvider: () async => directory,
    );
  });

  tearDown(() async {
    if (await directory.exists()) await directory.delete(recursive: true);
  });

  test('persists and reuses one key for an exact attempt payload', () async {
    final key = await store.keyFor(
      scopeKey: 'tenant-1|workspace-1|membership-1',
      deliveryId: _deliveryId,
      attemptId: _attemptId,
      payloadFingerprint: _fingerprint,
    );
    final restartedStore = FileBuyerReceiptIdempotencyStore(
      directoryProvider: () async => directory,
    );

    final retryKey = await restartedStore.keyFor(
      scopeKey: 'tenant-1|workspace-1|membership-1',
      deliveryId: _deliveryId,
      attemptId: _attemptId,
      payloadFingerprint: _fingerprint,
    );

    expect(retryKey, key);
    expect(key, matches(RegExp(r'^[0-9a-f-]{36}$')));
  });

  test('rejects changed payload for the same attempt', () async {
    await store.keyFor(
      scopeKey: 'scope-secret',
      deliveryId: _deliveryId,
      attemptId: _attemptId,
      payloadFingerprint: _fingerprint,
    );

    await expectLater(
      store.keyFor(
        scopeKey: 'scope-secret',
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        payloadFingerprint: _changedFingerprint,
      ),
      throwsA(isA<BuyerReceiptCommandConflict>()),
    );
  });

  test(
    'stores only hashed scope and payload identity, not receipt content',
    () async {
      await store.keyFor(
        scopeKey: 'tenant-private|workspace-private|membership-private',
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        payloadFingerprint: _fingerprint,
      );

      final serialized = await File(
        '${directory.path}/buyer-receipt-retries.json',
      ).readAsString();

      expect(serialized, isNot(contains('tenant-private')));
      expect(serialized, isNot(contains(_deliveryId)));
      expect(serialized, isNot(contains(_attemptId)));
      expect(serialized, isNot(contains('buyer-code')));
      expect(serialized, isNot(contains('Missing cartons')));
      final decoded = jsonDecode(serialized) as Map<String, dynamic>;
      expect(decoded['schemaVersion'], 1);
      expect((decoded['entries'] as Map<String, dynamic>).length, 1);
    },
  );

  test(
    'releases only the matching payload after definitive rejection',
    () async {
      final originalKey = await store.keyFor(
        scopeKey: 'scope-1',
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        payloadFingerprint: _fingerprint,
      );

      await expectLater(
        store.release(
          scopeKey: 'scope-1',
          deliveryId: _deliveryId,
          attemptId: _attemptId,
          payloadFingerprint: _changedFingerprint,
        ),
        throwsA(isA<BuyerReceiptCommandConflict>()),
      );
      await store.release(
        scopeKey: 'scope-1',
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        payloadFingerprint: _fingerprint,
      );
      final replacementKey = await store.keyFor(
        scopeKey: 'scope-1',
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        payloadFingerprint: _changedFingerprint,
      );

      expect(replacementKey, isNot(originalKey));
    },
  );
}
