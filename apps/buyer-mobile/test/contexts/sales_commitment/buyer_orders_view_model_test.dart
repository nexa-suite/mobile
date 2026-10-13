import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/buyer_orders_repository.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/presentation/buyer_orders_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test(
    'Buyer role without buyer.order.read cannot list or open orders',
    () async {
      final access = _FakeAccess(
        BuyerAccessSnapshot(
          status: BuyerAccessStatus.signedIn,
          authorityEpoch: 1,
          currentContext: const BuyerAccessContext(
            userId: 'user-1',
            membershipId: 'membership-1',
            tenantId: 'tenant-1',
            tenantName: 'Company',
            tenantSlug: 'company',
            workspaceId: 'workspace-1',
            workspaceName: 'Main',
            workspaceSlug: 'main',
            roles: {'BUYER'},
            permissions: {},
          ),
        ),
      );
      final repository = _CountingOrdersRepository();
      final listViewModel = BuyerOrdersViewModel(repository, access);
      final detailViewModel = BuyerOrderDetailViewModel(
        repository,
        access,
        'order-1',
      );

      await listViewModel.refresh();
      await detailViewModel.load();

      expect(repository.listCalls, 0);
      expect(repository.detailCalls, 0);
      expect(listViewModel.status, BuyerOrdersLoadStatus.permissionDenied);
      expect(detailViewModel.status, BuyerOrderDetailStatus.permissionDenied);
      expect(access.snapshot.isSignedIn, isTrue);

      listViewModel.dispose();
      detailViewModel.dispose();
      await access.dispose();
    },
  );
}

final class _CountingOrdersRepository implements BuyerOrdersRepository {
  int listCalls = 0;
  int detailCalls = 0;

  @override
  Future<BuyerOrdersPageProjection> list({required int page}) async {
    listCalls++;
    return BuyerOrdersPageProjection(
      items: const [],
      page: page,
      size: 25,
      total: 0,
    );
  }

  @override
  Future<BuyerOrderProjection> detail(String orderId) async {
    detailCalls++;
    return const BuyerOrderProjection(
      id: 'order-1',
      number: 'SO-0001',
      status: 'CONFIRMED',
      version: 1,
      lines: [],
    );
  }
}

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  final BuyerAccessSnapshot _snapshot;
  final StreamController<BuyerAccessSnapshot> _changes =
      StreamController<BuyerAccessSnapshot>.broadcast(sync: true);

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  @override
  Future<void> signIn({required String identifier, required String password}) =>
      throw UnimplementedError();

  @override
  Future<void> selectContext(String membershipId) => throw UnimplementedError();

  @override
  Future<void> signOut() => throw UnimplementedError();

  @override
  void invalidateLocalSession() => throw UnimplementedError();

  @override
  Future<void> dispose() => _changes.close();
}
