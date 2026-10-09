import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/infrastructure/file_purchase_request_idempotency_store.dart';

const _draftId = '00000000-0000-4000-8000-000000000001';
const _scopeKey = 'membership-1|tenant-1|workspace-1';
const _skuId = '00000000-0000-4000-8000-000000000002';
const _intentKey = '$_scopeKey|purchase-request-create|$_skuId';

void main() {
  test(
    'persists one UUID for each scope, draft, and version across instances',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'buyer-command-store-',
      );
      addTearDown(() => directory.delete(recursive: true));
      final store = FilePurchaseRequestIdempotencyStore(
        directoryProvider: () async => directory,
      );

      final first = await store.keyFor(
        scopeKey: 'membership-1|tenant-1|workspace-1',
        draftId: _draftId,
        version: 7,
      );
      final recreatedStore = FilePurchaseRequestIdempotencyStore(
        directoryProvider: () async => directory,
      );
      final retry = await recreatedStore.keyFor(
        scopeKey: 'membership-1|tenant-1|workspace-1',
        draftId: _draftId,
        version: 7,
      );

      expect(first, matches(RegExp(r'^[0-9a-f-]{36}$')));
      expect(retry, first);
      expect(
        await recreatedStore.keyFor(
          scopeKey: 'membership-2|tenant-1|workspace-1',
          draftId: _draftId,
          version: 7,
        ),
        isNot(first),
      );
      expect(
        await recreatedStore.keyFor(
          scopeKey: 'membership-1|tenant-1|workspace-1',
          draftId: _draftId,
          version: 8,
        ),
        isNot(first),
      );

      final stored = await File(
        '${directory.path}/buyer-purchase-request-commands.json',
      ).readAsString();
      expect(stored, isNot(contains('membership-1')));
      expect(stored, isNot(contains(_draftId)));
      expect(stored, contains(first));

      await recreatedStore.markCompleted(
        scopeKey: 'membership-1|tenant-1|workspace-1',
        draftId: _draftId,
        version: 7,
      );
      final next = await recreatedStore.keyFor(
        scopeKey: 'membership-1|tenant-1|workspace-1',
        draftId: _draftId,
        version: 7,
      );
      expect(next, isNot(first));
    },
  );

  test(
    'unknown create marker survives restart and needs explicit resolution',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'buyer-create-marker-',
      );
      addTearDown(() => directory.delete(recursive: true));
      final store = FilePurchaseRequestIdempotencyStore(
        directoryProvider: () async => directory,
      );

      await store.beginCreation(_intentKey);
      final recreated = FilePurchaseRequestIdempotencyStore(
        directoryProvider: () async => directory,
      );
      expect((await recreated.creationRecord(_intentKey))!.isUnknown, isTrue);
      await expectLater(
        recreated.beginCreation(_intentKey),
        throwsA(isA<FormatException>()),
      );

      final stored = await File(
        '${directory.path}/buyer-purchase-request-commands.json',
      ).readAsString();
      expect(stored, isNot(contains('membership-1')));
      expect(stored, isNot(contains('tenant-1')));
      expect(stored, isNot(contains(_skuId)));
      expect(stored, isNot(contains('access-secret')));

      await recreated.recordCreatedDraft(_intentKey, _draftId);
      expect((await store.creationRecord(_intentKey))!.draftId, _draftId);
      await recreated.clearCreationRecord(_intentKey);
      expect(await store.creationRecord(_intentKey), isNull);
    },
  );

  test('creation markers are isolated by Buyer scope and sellable SKU', () async {
    final directory = await Directory.systemTemp.createTemp(
      'buyer-create-scope-',
    );
    addTearDown(() => directory.delete(recursive: true));
    final store = FilePurchaseRequestIdempotencyStore(
      directoryProvider: () async => directory,
    );

    await store.beginCreation(_intentKey);
    expect(
      await store.creationRecord(
        'other-membership|tenant-1|workspace-1|purchase-request-create|$_skuId',
      ),
      isNull,
    );
    expect(
      await store.creationRecord(
        '$_scopeKey|purchase-request-create|00000000-0000-4000-8000-000000000009',
      ),
      isNull,
    );
  });
}
