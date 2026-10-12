import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/presentation/catalog_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test('a catalog response from a signed-out authority is discarded', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final response = Completer<CatalogPageProjection>();
    final viewModel = CatalogViewModel(
      _ControlledCatalogRepository(response),
      access,
    );

    final pendingLoad = viewModel.refresh();
    expect(viewModel.status, CatalogLoadStatus.loading);

    access.emit(
      const BuyerAccessSnapshot(
        status: BuyerAccessStatus.signedOut,
        authorityEpoch: 2,
      ),
    );
    expect(viewModel.status, CatalogLoadStatus.idle);
    expect(viewModel.items, isEmpty);

    response.complete(
      const CatalogPageProjection(
        items: [
          CatalogItemProjection(
            catalogItemId: 'CAT-0001',
            itemName: 'Old context item',
            availabilityStatus: 'AVAILABLE',
          ),
        ],
        page: 0,
        size: 20,
        totalItems: 1,
        totalPages: 1,
      ),
    );
    await pendingLoad;

    expect(viewModel.status, CatalogLoadStatus.idle);
    expect(viewModel.items, isEmpty);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'a permission denial does not erase the signed-in Buyer authority',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final viewModel = CatalogViewModel(_ForbiddenCatalogRepository(), access);

      await viewModel.refresh();

      expect(viewModel.status, CatalogLoadStatus.unavailable);
      expect(access.snapshot.isSignedIn, isTrue);

      viewModel.dispose();
      await access.dispose();
    },
  );
}

BuyerAccessSnapshot _signedInSnapshot() => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: 1,
  currentContext: const BuyerAccessContext(
    membershipId: 'membership-1',
    tenantId: 'tenant-1',
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: 'workspace-1',
    workspaceName: 'Main',
    workspaceSlug: 'main',
  ),
);

final class _ControlledCatalogRepository implements CatalogRepository {
  _ControlledCatalogRepository(this._response);

  final Completer<CatalogPageProjection> _response;

  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
  }) => _response.future;

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) =>
      throw UnimplementedError();
}

final class _ForbiddenCatalogRepository implements CatalogRepository {
  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
  }) => Future.error(
    const NexaApiFailure(
      code: 'permission_denied',
      statusCode: 403,
      userMessage: 'This information is not available for your current access.',
    ),
  );

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) =>
      throw UnimplementedError();
}

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final StreamController<BuyerAccessSnapshot> _changes =
      StreamController<BuyerAccessSnapshot>.broadcast(sync: true);

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
  void invalidateLocalSession() =>
      emit(const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut));

  @override
  Future<void> dispose() => _changes.close();
}
