import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_deliveries_repository.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/presentation/buyer_deliveries_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000001';

void main() {
  test(
    'authority change clears list and discards a pending page response',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final pendingNextPage = Completer<BuyerDeliveryPageProjection>();
      final repository = _Repository((page) {
        if (page == 0) return Future.value(_page(0, 26));
        return pendingNextPage.future;
      });
      final viewModel = BuyerDeliveriesViewModel(repository, access);

      await viewModel.refresh();
      expect(viewModel.items, hasLength(1));
      final pending = viewModel.nextPage();
      expect(repository.pages, [0, 1]);

      access.emit(_signedInSnapshot(epoch: 2, tenantId: 'tenant-new'));
      expect(viewModel.items, isEmpty);
      expect(viewModel.total, 0);
      expect(viewModel.status, BuyerDeliveriesStatus.idle);

      pendingNextPage.complete(_page(1, 26, dispatchNumber: 'OLD-CONTEXT'));
      await pending;
      expect(viewModel.items, isEmpty);
      expect(viewModel.status, BuyerDeliveriesStatus.idle);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('stale detail completion is discarded after context changes', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final pendingDetail = Completer<BuyerDeliveryDetailProjection>();
    final repository = _Repository((_) async => _page(0, 1))
      ..detailResult = pendingDetail.future;
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
    );

    final pending = viewModel.load();
    access.emit(_signedInSnapshot(epoch: 2, tenantId: 'tenant-new'));
    expect(viewModel.delivery, isNull);
    expect(viewModel.events, isEmpty);

    pendingDetail.complete(
      BuyerDeliveryDetailProjection(
        delivery: _delivery('PRIVATE OLD CONTEXT'),
        events: const [],
      ),
    );
    await pending;
    expect(viewModel.delivery, isNull);
    expect(viewModel.events, isEmpty);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'detail checks the exact tracking grant before making a request',
    () async {
      final access = _FakeAccess(_signedInSnapshot(permissions: const {}));
      final repository = _Repository((_) async => _page(0, 1));
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
      );

      await viewModel.load();

      expect(viewModel.status, BuyerDeliveryDetailStatus.permissionDenied);
      expect(viewModel.delivery, isNull);
      expect(viewModel.events, isEmpty);
      expect(repository.detailCalls, 0);
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'detail 403 keeps the Buyer session while denying this capability',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _Repository((_) async => _page(0, 1))
        ..detailResult = Future.error(
          const NexaApiFailure(
            code: 'permission_denied',
            statusCode: 403,
            userMessage: 'Not permitted.',
          ),
        );
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
      );

      await viewModel.load();

      expect(viewModel.status, BuyerDeliveryDetailStatus.permissionDenied);
      expect(viewModel.delivery, isNull);
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('detail 401 clears the session and private detail state', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final repository = _Repository((_) async => _page(0, 1))
      ..detailResult = Future.error(
        const NexaApiFailure(
          code: 'session_expired',
          statusCode: 401,
          userMessage: 'Sign in again.',
        ),
      );
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
    );

    await viewModel.load();

    expect(viewModel.delivery, isNull);
    expect(viewModel.events, isEmpty);
    expect(access.snapshot.isSignedIn, isFalse);
    expect(access.invalidations, 1);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'detail ACCESS_CONTEXT_INVALID clears the active scope and private data',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _Repository((_) async => _page(0, 1))
        ..detailResult = Future.error(
          const NexaApiFailure(
            code: 'ACCESS_CONTEXT_INVALID',
            statusCode: 403,
            userMessage: 'The active access context is invalid.',
          ),
        );
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
      );

      await viewModel.load();

      expect(viewModel.delivery, isNull);
      expect(viewModel.events, isEmpty);
      expect(access.snapshot.isSignedIn, isFalse);
      expect(access.invalidations, 1);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'detail response cannot publish after silent access lease change',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final pendingDetail = Completer<BuyerDeliveryDetailProjection>();
      final repository = _Repository((_) async => _page(0, 1))
        ..detailResult = pendingDetail.future;
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
      );

      final pending = viewModel.load();
      access.replaceWithoutNotification(
        _signedInSnapshot(epoch: 2, tenantId: 'tenant-new'),
      );
      pendingDetail.complete(
        BuyerDeliveryDetailProjection(
          delivery: _delivery('NEW-AUTHORITY RESPONSE'),
          events: const [],
        ),
      );
      await pending;

      expect(viewModel.delivery, isNull);
      expect(viewModel.events, isEmpty);
      expect(viewModel.status, BuyerDeliveryDetailStatus.unavailable);
      expect(
        viewModel.message,
        'El contexto activo cambió. Vuelve a abrir esta entrega.',
      );

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'a generic 403 denies tracking without invalidating the session',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _Repository(
        (_) => Future.error(
          const NexaApiFailure(
            code: 'permission_denied',
            statusCode: 403,
            userMessage: 'Not permitted.',
          ),
        ),
      );
      final viewModel = BuyerDeliveriesViewModel(repository, access);

      await viewModel.refresh();

      expect(viewModel.status, BuyerDeliveriesStatus.permissionDenied);
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'ACCESS_CONTEXT_INVALID clears private data and current authority',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _Repository(
        (_) => Future.error(
          const NexaApiFailure(
            code: 'ACCESS_CONTEXT_INVALID',
            statusCode: 403,
            userMessage: 'The access context is no longer valid.',
          ),
        ),
      );
      final viewModel = BuyerDeliveriesViewModel(repository, access);

      await viewModel.refresh();

      expect(viewModel.items, isEmpty);
      expect(viewModel.status, BuyerDeliveriesStatus.idle);
      expect(viewModel.message, isNull);
      expect(access.snapshot.isSignedIn, isFalse);
      expect(access.invalidations, 1);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'tracking permission is checked locally and pagination stays bounded',
    () async {
      final noGrant = _FakeAccess(_signedInSnapshot(permissions: const {}));
      final uncalled = _Repository((_) async => _page(0, 1));
      final deniedModel = BuyerDeliveriesViewModel(uncalled, noGrant);
      await deniedModel.refresh();
      expect(deniedModel.status, BuyerDeliveriesStatus.permissionDenied);
      expect(uncalled.pages, isEmpty);
      expect(noGrant.snapshot.isSignedIn, isTrue);
      deniedModel.dispose();
      await noGrant.dispose();

      final access = _FakeAccess(_signedInSnapshot());
      final repository = _Repository((page) async => _page(page, 26));
      final viewModel = BuyerDeliveriesViewModel(repository, access);
      await viewModel.refresh();
      await viewModel.previousPage();
      expect(repository.pages, [0]);
      await viewModel.nextPage();
      expect(repository.pages, [0, 1]);
      await viewModel.nextPage();
      expect(repository.pages, [0, 1]);
      expect(viewModel.page, 1);
      expect(BuyerDeliveriesViewModel.pageSize, 25);

      viewModel.dispose();
      await access.dispose();
    },
  );
}

BuyerDeliveryPageProjection _page(
  int page,
  int total, {
  String dispatchNumber = 'DO-0001',
}) => BuyerDeliveryPageProjection(
  items: [_delivery(dispatchNumber)],
  page: page,
  size: 25,
  total: total,
);

BuyerDeliveryProjection _delivery(String dispatchNumber) =>
    BuyerDeliveryProjection(
      id: _deliveryId,
      dispatchNumber: dispatchNumber,
      salesOrderNumber: 'SO-0001',
      status: 'IN_TRANSIT',
      destination: 'Sucursal principal',
      updatedAt: DateTime.utc(2026, 10, 9),
      alerts: const [],
    );

BuyerDeliveryDetailProjection _detail() => BuyerDeliveryDetailProjection(
  delivery: _delivery('DO-0001'),
  events: [
    BuyerDeliveryEventProjection(
      id: '00000000-0000-4000-8000-000000000002',
      type: 'IN_TRANSIT',
      occurredAt: DateTime.utc(2026, 10, 9),
      summary: 'En tránsito.',
    ),
  ],
);

BuyerAccessSnapshot _signedInSnapshot({
  int epoch = 1,
  String tenantId = 'tenant-1',
  Set<String> permissions = const {'buyer.tracking.read'},
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    membershipId: 'membership-1',
    tenantId: tenantId,
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: 'workspace-1',
    workspaceName: 'Main',
    workspaceSlug: 'main',
    roles: const {'BUYER'},
    permissions: permissions,
    authorizationVersion: 7,
  ),
);

final class _Repository implements BuyerDeliveriesRepository {
  _Repository(this.listResult);

  final Future<BuyerDeliveryPageProjection> Function(int page) listResult;
  final pages = <int>[];
  Future<BuyerDeliveryDetailProjection>? detailResult;
  int detailCalls = 0;

  @override
  Future<BuyerDeliveryPageProjection> list({required int page}) {
    pages.add(page);
    return listResult(page);
  }

  @override
  Future<BuyerDeliveryDetailProjection> detail(String dispatchId) {
    detailCalls++;
    return detailResult ?? Future.value(_detail());
  }
}

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast(sync: true);
  int invalidations = 0;

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  void emit(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
    _changes.add(snapshot);
  }

  void replaceWithoutNotification(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
  }

  @override
  Future<void> signIn({required String identifier, required String password}) =>
      throw UnimplementedError();

  @override
  Future<void> selectContext(String membershipId) => throw UnimplementedError();

  @override
  Future<void> signOut() => throw UnimplementedError();

  @override
  void invalidateLocalSession() {
    invalidations++;
    emit(const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut));
  }

  @override
  Future<void> dispose() => _changes.close();
}
