import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_delivery_summary_cache.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/secure_buyer_delivery_summary_cache.dart';

const _scope = BuyerDeliverySummaryCacheScope(
  userId: '00000000-0000-4000-8000-000000000001',
  tenantId: '00000000-0000-4000-8000-000000000002',
  workspaceId: '00000000-0000-4000-8000-000000000003',
  membershipId: '00000000-0000-4000-8000-000000000004',
  authorityFingerprint: 'v7|buyer.tracking.read',
);

void main() {
  test(
    'failed deletion leaves a durable denial marker across adapter reopen',
    () async {
      final storage = _MemorySecureStorage();
      final cache = SecureBuyerDeliverySummaryCache(secureStorage: storage);
      final snapshot = BuyerDeliverySummaryCacheSnapshot(
        items: [
          BuyerDeliverySummaryProjection(
            deliveryId: '00000000-0000-4000-8000-000000000010',
            orderNumber: 'SO-1042',
            status: 'DISPATCHED',
            updatedAt: DateTime.utc(2026, 11, 16),
          ),
        ],
        asOf: DateTime.utc(2026, 11, 16),
      );
      await cache.write(scope: _scope, snapshot: snapshot);
      storage.failDelete = true;
      await expectLater(cache.delete(scope: _scope), throwsStateError);
      expect(await cache.read(scope: _scope), isNull);
      final reopened = SecureBuyerDeliverySummaryCache(secureStorage: storage);
      expect(await reopened.read(scope: _scope), isNull);
      await reopened.write(scope: _scope, snapshot: snapshot);
      expect(await reopened.read(scope: _scope), isNotNull);
    },
  );

  test(
    'persists only bounded summary fields under a hashed full scope',
    () async {
      final storage = _MemorySecureStorage();
      final cache = SecureBuyerDeliverySummaryCache(secureStorage: storage);
      final asOf = DateTime.utc(2026, 11, 16, 12, 30);
      final summary = BuyerDeliverySummaryProjection(
        deliveryId: '00000000-0000-4000-8000-000000000010',
        orderNumber: 'SO-1042',
        status: 'DISPATCHED',
        updatedAt: DateTime.utc(2026, 11, 16, 12),
      );

      await cache.write(
        scope: _scope,
        snapshot: BuyerDeliverySummaryCacheSnapshot(
          items: [summary],
          asOf: asOf,
        ),
      );

      final encoded = storage.values.values.single;
      expect(encoded, contains('SO-1042'));
      expect(encoded, contains('DISPATCHED'));
      expect(encoded, contains('deliveryId'));
      expect(encoded, isNot(contains('tenantId')));
      expect(encoded, isNot(contains('authorityFingerprint')));
      expect(encoded, isNot(contains('destination')));
      expect(encoded, isNot(contains('email')));
      expect(encoded, isNot(contains('amount')));
      expect(encoded, isNot(contains(_scope.userId)));
      expect(encoded, isNot(contains(_scope.tenantId)));
      expect(encoded, isNot(contains(_scope.workspaceId)));
      expect(encoded, isNot(contains(_scope.membershipId)));

      final restored = await cache.read(scope: _scope);
      expect(restored?.asOf, asOf);
      expect(restored?.items, hasLength(1));
      expect(restored?.items.single.deliveryId, summary.deliveryId);
      expect(restored?.items.single.orderNumber, summary.orderNumber);
      expect(restored?.items.single.status, summary.status);
      expect(restored?.items.single.updatedAt, summary.updatedAt);

      final otherUser = BuyerDeliverySummaryCacheScope(
        userId: '00000000-0000-4000-8000-000000000099',
        tenantId: _scope.tenantId,
        workspaceId: _scope.workspaceId,
        membershipId: _scope.membershipId,
        authorityFingerprint: _scope.authorityFingerprint,
      );
      expect(await cache.read(scope: otherUser), isNull);

      final otherTenant = BuyerDeliverySummaryCacheScope(
        userId: _scope.userId,
        tenantId: '00000000-0000-4000-8000-000000000099',
        workspaceId: _scope.workspaceId,
        membershipId: _scope.membershipId,
        authorityFingerprint: _scope.authorityFingerprint,
      );
      final otherWorkspace = BuyerDeliverySummaryCacheScope(
        userId: _scope.userId,
        tenantId: _scope.tenantId,
        workspaceId: '00000000-0000-4000-8000-000000000099',
        membershipId: _scope.membershipId,
        authorityFingerprint: _scope.authorityFingerprint,
      );
      final otherMembership = BuyerDeliverySummaryCacheScope(
        userId: _scope.userId,
        tenantId: _scope.tenantId,
        workspaceId: _scope.workspaceId,
        membershipId: '00000000-0000-4000-8000-000000000099',
        authorityFingerprint: _scope.authorityFingerprint,
      );
      final changedAuthorization = BuyerDeliverySummaryCacheScope(
        userId: _scope.userId,
        tenantId: _scope.tenantId,
        workspaceId: _scope.workspaceId,
        membershipId: _scope.membershipId,
        authorityFingerprint: 'v8|buyer.order.read',
      );
      for (final differentScope in [
        otherTenant,
        otherWorkspace,
        otherMembership,
        changedAuthorization,
      ]) {
        expect(await cache.read(scope: differentScope), isNull);
      }
    },
  );

  test(
    'rejects overlarge or malformed cache data and deletes corrupt bytes',
    () async {
      final storage = _MemorySecureStorage();
      final cache = SecureBuyerDeliverySummaryCache(secureStorage: storage);
      final item = BuyerDeliverySummaryProjection(
        deliveryId: '00000000-0000-4000-8000-000000000010',
        orderNumber: 'SO-1042',
        status: 'DISPATCHED',
        updatedAt: DateTime.utc(2026, 11, 16),
      );

      await expectLater(
        cache.write(
          scope: _scope,
          snapshot: BuyerDeliverySummaryCacheSnapshot(
            items: List.filled(
              SecureBuyerDeliverySummaryCache.maximumItems + 1,
              item,
            ),
            asOf: DateTime.utc(2026, 11, 16),
          ),
        ),
        throwsFormatException,
      );

      await cache.write(
        scope: _scope,
        snapshot: BuyerDeliverySummaryCacheSnapshot(
          items: [item],
          asOf: DateTime.utc(2026, 11, 16),
        ),
      );
      final key = storage.values.keys.single;
      storage.values[key] = '{invalid-json';

      expect(await cache.read(scope: _scope), isNull);
      expect(storage.values, isEmpty);
    },
  );

  test('requires complete UUID scope before reading or writing', () async {
    final cache = SecureBuyerDeliverySummaryCache(
      secureStorage: _MemorySecureStorage(),
    );
    const incompleteScope = BuyerDeliverySummaryCacheScope(
      userId: 'user-1',
      tenantId: 'tenant-1',
      workspaceId: 'workspace-1',
      membershipId: 'membership-1',
      authorityFingerprint: 'active',
    );

    await expectLater(
      cache.read(scope: incompleteScope),
      throwsFormatException,
    );
  });
}

final class _MemorySecureStorage implements BuyerDeliverySummarySecureStorage {
  final values = <String, String>{};
  bool failDelete = false;

  @override
  Future<String?> read({required String key}) async => values[key];

  @override
  Future<void> write({required String key, required String value}) async {
    values[key] = value;
  }

  @override
  Future<void> delete({required String key}) async {
    if (failDelete) throw StateError('secure deletion failed');
    values.remove(key);
  }
}
