import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/buyer_orders_repository.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/presentation/buyer_orders_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

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

  test(
    '403 clears list and detail projections without invalidating session',
    () async {
      final access = _FakeAccess(_authorizedSnapshot());
      final repository = _CountingOrdersRepository();
      final list = BuyerOrdersViewModel(repository, access);
      final detail = BuyerOrderDetailViewModel(repository, access, 'order-1');

      await list.refresh();
      await detail.load();
      expect(list.items, hasLength(1));
      expect(detail.order, isNotNull);

      repository.listResult = Future.error(_forbidden);
      repository.detailResult = Future.error(_forbidden);
      await list.refresh();
      await detail.load();

      expect(list.items, isEmpty);
      expect(list.status, BuyerOrdersLoadStatus.permissionDenied);
      expect(detail.order, isNull);
      expect(detail.status, BuyerOrderDetailStatus.permissionDenied);
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);

      list.dispose();
      detail.dispose();
      await access.dispose();
    },
  );

  test('order detail discards a response after scope changes', () async {
    final access = _FakeAccess(_authorizedSnapshot());
    final pending = Completer<BuyerOrderProjection>();
    final repository = _CountingOrdersRepository()
      ..detailResult = pending.future;
    final detail = BuyerOrderDetailViewModel(repository, access, 'order-1');

    final loading = detail.load();
    access.emit(_authorizedSnapshot(epoch: 2, tenantId: 'tenant-2'));
    pending.complete(_order(number: 'PRIVATE OLD SCOPE'));
    await loading;

    expect(detail.order, isNull);
    expect(detail.status, BuyerOrderDetailStatus.unavailable);
    expect(detail.message, contains('contexto activo cambió'));

    detail.dispose();
    await access.dispose();
  });
}

BuyerAccessSnapshot _authorizedSnapshot({
  int epoch = 1,
  String tenantId = 'tenant-1',
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    userId: 'user-1',
    membershipId: 'membership-1',
    tenantId: tenantId,
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: 'workspace-1',
    workspaceName: 'Main',
    workspaceSlug: 'main',
    roles: const {'BUYER'},
    permissions: const {'buyer.order.read'},
    authorizationVersion: 2,
  ),
);

BuyerOrderProjection _order({String number = 'SO-0001'}) =>
    BuyerOrderProjection(
      id: 'order-1',
      number: number,
      status: 'CONFIRMED',
      version: 1,
      lines: const [],
    );

const _forbidden = NexaApiFailure(
  code: 'permission_denied',
  statusCode: 403,
  userMessage: 'Not permitted.',
);

final class _CountingOrdersRepository implements BuyerOrdersRepository {
  int listCalls = 0;
  int detailCalls = 0;
  Future<BuyerOrdersPageProjection>? listResult;
  Future<BuyerOrderProjection>? detailResult;

  @override
  Future<BuyerOrdersPageProjection> list({required int page}) async {
    listCalls++;
    final configured = listResult;
    if (configured != null) return configured;
    return BuyerOrdersPageProjection(
      items: [_order()],
      page: page,
      size: 25,
      total: 0,
    );
  }

  @override
  Future<BuyerOrderProjection> detail(String orderId) async {
    detailCalls++;
    return detailResult ?? _order();
  }
}

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final StreamController<BuyerAccessSnapshot> _changes =
      StreamController<BuyerAccessSnapshot>.broadcast(sync: true);
  int invalidations = 0;

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  void emit(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
    _changes.add(snapshot);
  }

  @override
  Future<void> signIn({required String identifier, required String password}) =>
      throw UnimplementedError();

  @override
  Future<void> selectContext(String membershipId) => throw UnimplementedError();

  @override
  Future<void> signOut() => throw UnimplementedError();

  @override
  Future<bool> lockForBiometricLogin() async => false;

  @override
  Future<BuyerBiometricLoginStatus> biometricLoginStatus() async =>
      const BuyerBiometricLoginStatus();

  @override
  Future<bool> enableBiometricLogin() async => false;

  @override
  Future<void> disableBiometricLogin() async {}

  @override
  Future<void> signInWithBiometrics() async {}
  @override
  void invalidateLocalSession() {
    invalidations++;
    emit(const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut));
  }

  @override
  Future<void> dispose() => _changes.close();
}
