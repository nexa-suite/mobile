import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_receipt_draft_store.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/secure_buyer_receipt_draft_store.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000001';
const _attemptId = '00000000-0000-4000-8000-000000000002';

void main() {
  test(
    'restores a harmless draft after recreating the store in the same scope',
    () async {
      final storage = _MemorySecureStorage();
      final original = SecureBuyerReceiptDraftStore(secureStorage: storage);
      const draft = BuyerReceiptDraft(
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        decision: 'DISPUTED',
        acceptedQuantity: '3.5',
        reason: 'Falta una caja',
      );

      await original.write(
        scopeKey: 'membership-a|tenant-a|workspace-a',
        draft: draft,
      );
      final afterProcessDeath = SecureBuyerReceiptDraftStore(
        secureStorage: storage,
      );

      final restored = await afterProcessDeath.read(
        scopeKey: 'membership-a|tenant-a|workspace-a',
        deliveryId: _deliveryId,
      );
      expect(restored?.decision, draft.decision);
      expect(restored?.acceptedQuantity, draft.acceptedQuantity);
      expect(restored?.reason, draft.reason);
      expect(restored?.attemptId, draft.attemptId);
      expect(
        await afterProcessDeath.read(
          scopeKey: 'membership-b|tenant-a|workspace-a',
          deliveryId: _deliveryId,
        ),
        isNull,
      );
    },
  );

  test(
    'draft storage identity hides raw scope and contains no handoff secret',
    () async {
      final storage = _MemorySecureStorage();
      final store = SecureBuyerReceiptDraftStore(secureStorage: storage);
      await store.write(
        scopeKey: 'membership-private|tenant-private|workspace-private',
        draft: const BuyerReceiptDraft(
          deliveryId: _deliveryId,
          attemptId: _attemptId,
          decision: 'DISPUTED',
          acceptedQuantity: '2',
          reason: 'Falta una caja',
        ),
      );

      expect(storage.values, hasLength(1));
      expect(storage.keys.single, startsWith('buyer_receipt_draft_v1_'));
      expect(storage.keys.single, isNot(contains('membership-private')));
      expect(storage.values.single, isNot(contains('membership-private')));
      expect(storage.values.single, isNot(contains('qr-secret')));
      final encoded = jsonDecode(storage.values.single) as Map<String, dynamic>;
      expect(encoded.keys, {
        'schemaVersion',
        'deliveryId',
        'attemptId',
        'decision',
        'acceptedQuantity',
        'reason',
      });
    },
  );

  test('deleting a different scope cannot remove the saved draft', () async {
    final storage = _MemorySecureStorage();
    final store = SecureBuyerReceiptDraftStore(secureStorage: storage);
    await store.write(
      scopeKey: 'scope-a',
      draft: const BuyerReceiptDraft(
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        decision: 'ACCEPTED',
        acceptedQuantity: '4',
        reason: '',
      ),
    );

    await store.delete(scopeKey: 'scope-b', deliveryId: _deliveryId);

    expect(
      await store.read(scopeKey: 'scope-a', deliveryId: _deliveryId),
      isNotNull,
    );
  });

  test('rejects malformed draft data rather than restoring it', () async {
    final storage = _MemorySecureStorage();
    final store = SecureBuyerReceiptDraftStore(secureStorage: storage);
    await store.write(
      scopeKey: 'scope-a',
      draft: const BuyerReceiptDraft(
        deliveryId: _deliveryId,
        attemptId: _attemptId,
        decision: 'ACCEPTED',
        acceptedQuantity: '4',
        reason: '',
      ),
    );
    storage.overwrite(storage.keys.single, '{"schemaVersion":99}');

    await expectLater(
      store.read(scopeKey: 'scope-a', deliveryId: _deliveryId),
      throwsA(isA<FormatException>()),
    );
  });
}

final class _MemorySecureStorage implements BuyerReceiptDraftSecureStorage {
  final Map<String, String> _entries = {};

  Iterable<String> get keys => _entries.keys;
  Iterable<String> get values => _entries.values;

  void overwrite(String key, String value) => _entries[key] = value;

  @override
  Future<String?> read({required String key}) async => _entries[key];

  @override
  Future<void> write({required String key, required String value}) async {
    _entries[key] = value;
  }

  @override
  Future<void> delete({required String key}) async {
    _entries.remove(key);
  }
}
