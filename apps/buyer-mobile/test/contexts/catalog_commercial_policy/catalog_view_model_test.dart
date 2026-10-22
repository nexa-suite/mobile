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

      expect(viewModel.status, CatalogLoadStatus.permissionDenied);
      expect(access.snapshot.isSignedIn, isTrue);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'a catalog 403 clears products retained from a previous response',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _RecordingCatalogRepository();
      final viewModel = CatalogViewModel(repository, access);

      await viewModel.refresh();
      repository.denyList = true;
      await viewModel.refresh();

      expect(viewModel.status, CatalogLoadStatus.permissionDenied);
      expect(viewModel.items, isEmpty);
      expect(access.snapshot.isSignedIn, isTrue);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('a Buyer role without catalog.read cannot query the catalog', () async {
    final access = _FakeAccess(_signedInSnapshot(permissions: const {}));
    final repository = _CountingCatalogRepository();
    final viewModel = CatalogViewModel(repository, access);

    await viewModel.refresh();

    expect(repository.listCalls, 0);
    expect(viewModel.status, CatalogLoadStatus.permissionDenied);
    expect(access.snapshot.isSignedIn, isTrue);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'sends product, brand, and type filters before changing pages',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _RecordingCatalogRepository();
      final viewModel = CatalogViewModel(repository, access);

      await viewModel.search('leche');
      await viewModel.filter(brand: ' Valle ', category: ' Bebidas ');
      await viewModel.nextPage();

      expect(repository.calls, [
        {
          'query': 'leche',
          'brand': '',
          'brandId': '',
          'category': '',
          'categoryId': '',
          'familyId': '',
          'variantId': '',
          'page': 0,
        },
        {
          'query': 'leche',
          'brand': 'Valle',
          'brandId': '',
          'category': 'Bebidas',
          'categoryId': '',
          'familyId': '',
          'variantId': '',
          'page': 0,
        },
        {
          'query': 'leche',
          'brand': 'Valle',
          'brandId': '',
          'category': 'Bebidas',
          'categoryId': '',
          'familyId': '',
          'variantId': '',
          'page': 1,
        },
      ]);
      expect(viewModel.brand, 'Valle');
      expect(viewModel.category, 'Bebidas');

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'variety options and catalog results stay scoped to selected family',
    () async {
      const familyId = '00000000-0000-4000-8000-000000000021';
      const variantId = '00000000-0000-4000-8000-000000000022';
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _RecordingCatalogRepository();
      final viewModel = CatalogViewModel(repository, access);

      await viewModel.loadFilterOptions(
        kind: CatalogFilterKind.family,
        search: '',
        page: 0,
      );
      await viewModel.selectFilterOption(
        kind: CatalogFilterKind.family,
        option: const CatalogFilterOptionProjection(
          id: familyId,
          name: 'Lácteos',
        ),
      );
      await viewModel.loadFilterOptions(
        kind: CatalogFilterKind.variant,
        search: '',
        page: 0,
      );
      await viewModel.selectFilterOption(
        kind: CatalogFilterKind.variant,
        option: const CatalogFilterOptionProjection(
          id: variantId,
          name: 'Edam',
        ),
      );

      expect(repository.lookupCalls, [
        {
          'kind': CatalogFilterKind.family,
          'search': '',
          'page': 0,
          'familyId': null,
        },
        {
          'kind': CatalogFilterKind.variant,
          'search': '',
          'page': 0,
          'familyId': familyId,
        },
      ]);
      expect(repository.calls.last['familyId'], familyId);
      expect(repository.calls.last['variantId'], variantId);
      expect(viewModel.family, 'Lácteos');
      expect(viewModel.variant, 'Edam');

      await viewModel.selectFilterOption(
        kind: CatalogFilterKind.family,
        option: const CatalogFilterOptionProjection(
          id: '00000000-0000-4000-8000-000000000023',
          name: 'Bebidas',
        ),
      );

      expect(viewModel.variant, '');
      expect(viewModel.variantId, '');
      expect(
        viewModel.filterOptionsState(CatalogFilterKind.variant).status,
        CatalogFilterOptionsStatus.idle,
      );
      expect(
        repository.calls.last['familyId'],
        '00000000-0000-4000-8000-000000000023',
      );
      expect(repository.calls.last['variantId'], '');

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('clears active catalog filters when the Buyer scope changes', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final repository = _RecordingCatalogRepository();
    final viewModel = CatalogViewModel(repository, access);

    await viewModel.filter(brand: 'Valle', category: 'Bebidas');
    access.emit(_signedInSnapshot(epoch: 2));

    expect(viewModel.query, '');
    expect(viewModel.brand, '');
    expect(viewModel.category, '');
    expect(viewModel.items, isEmpty);
    expect(viewModel.status, CatalogLoadStatus.idle);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'loads referential filter options only with Buyer catalog access',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _RecordingCatalogRepository();
      final viewModel = CatalogViewModel(repository, access);

      await viewModel.loadFilterOptions(
        kind: CatalogFilterKind.category,
        search: 'Beb',
        page: 1,
      );

      expect(repository.lookupCalls, [
        {
          'kind': CatalogFilterKind.category,
          'search': 'Beb',
          'page': 1,
          'familyId': null,
        },
      ]);
      expect(
        viewModel.filterOptionsState(CatalogFilterKind.category).status,
        CatalogFilterOptionsStatus.current,
      );

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('does not query taxonomy options without catalog.read', () async {
    final access = _FakeAccess(_signedInSnapshot(permissions: const {}));
    final repository = _RecordingCatalogRepository();
    final viewModel = CatalogViewModel(repository, access);

    await viewModel.loadFilterOptions(
      kind: CatalogFilterKind.brand,
      search: '',
      page: 0,
    );

    expect(repository.lookupCalls, isEmpty);
    expect(
      viewModel.filterOptionsState(CatalogFilterKind.brand).status,
      CatalogFilterOptionsStatus.permissionDenied,
    );

    viewModel.dispose();
    await access.dispose();
  });

  test('does not load variety choices before a family is selected', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final repository = _RecordingCatalogRepository();
    final viewModel = CatalogViewModel(repository, access);

    await viewModel.loadFilterOptions(
      kind: CatalogFilterKind.variant,
      search: '',
      page: 0,
    );

    expect(repository.lookupCalls, isEmpty);
    expect(
      viewModel.filterOptionsState(CatalogFilterKind.variant).status,
      CatalogFilterOptionsStatus.unavailable,
    );

    viewModel.dispose();
    await access.dispose();
  });
}

BuyerAccessSnapshot _signedInSnapshot({
  Set<String> permissions = const {'catalog.read'},
  int epoch = 1,
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    userId: 'user-1',
    membershipId: 'membership-1',
    tenantId: 'tenant-1',
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: 'workspace-1',
    workspaceName: 'Main',
    workspaceSlug: 'main',
    roles: {'BUYER'},
    permissions: permissions,
  ),
);

final class _ControlledCatalogRepository implements CatalogRepository {
  _ControlledCatalogRepository(this._response);

  final Completer<CatalogPageProjection> _response;

  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
    String brand = '',
    String category = '',
    String brandId = '',
    String categoryId = '',
    String familyId = '',
    String variantId = '',
  }) => _response.future;

  @override
  Future<CatalogFilterOptionPageProjection> filterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) async => CatalogFilterOptionPageProjection(
    items: const [],
    page: page,
    size: 100,
    total: 0,
  );

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) =>
      throw UnimplementedError();
}

final class _RecordingCatalogRepository implements CatalogRepository {
  final calls = <Map<String, Object>>[];
  final lookupCalls = <Map<String, Object?>>[];
  bool denyList = false;

  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
    String brand = '',
    String category = '',
    String brandId = '',
    String categoryId = '',
    String familyId = '',
    String variantId = '',
  }) async {
    calls.add({
      'query': query,
      'brand': brand,
      'brandId': brandId,
      'category': category,
      'categoryId': categoryId,
      'familyId': familyId,
      'variantId': variantId,
      'page': page,
    });
    if (denyList) {
      throw const NexaApiFailure(
        code: 'permission_denied',
        statusCode: 403,
        userMessage: 'Not permitted.',
      );
    }
    return CatalogPageProjection(
      items: const [
        CatalogItemProjection(
          catalogItemId: 'CAT-0001',
          itemName: 'Leche',
          availabilityStatus: 'AVAILABLE',
        ),
      ],
      page: page,
      size: 20,
      totalItems: 60,
      totalPages: 3,
    );
  }

  @override
  Future<CatalogFilterOptionPageProjection> filterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) async {
    lookupCalls.add({
      'kind': kind,
      'search': search,
      'page': page,
      'familyId': familyId,
    });
    return CatalogFilterOptionPageProjection(
      items: const [
        CatalogFilterOptionProjection(
          id: '00000000-0000-4000-8000-000000000021',
          name: 'Lácteos',
        ),
      ],
      page: page,
      size: 100,
      total: 101,
    );
  }

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) =>
      throw UnimplementedError();
}

final class _ForbiddenCatalogRepository implements CatalogRepository {
  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
    String brand = '',
    String category = '',
    String brandId = '',
    String categoryId = '',
    String familyId = '',
    String variantId = '',
  }) => Future.error(
    const NexaApiFailure(
      code: 'permission_denied',
      statusCode: 403,
      userMessage: 'This information is not available for your current access.',
    ),
  );

  @override
  Future<CatalogFilterOptionPageProjection> filterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) => Future.error(
    const NexaApiFailure(
      code: 'permission_denied',
      statusCode: 403,
      userMessage: 'Not permitted.',
    ),
  );

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) =>
      throw UnimplementedError();
}

final class _CountingCatalogRepository implements CatalogRepository {
  int listCalls = 0;

  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
    String brand = '',
    String category = '',
    String brandId = '',
    String categoryId = '',
    String familyId = '',
    String variantId = '',
  }) {
    listCalls++;
    throw UnimplementedError();
  }

  @override
  Future<CatalogFilterOptionPageProjection> filterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) {
    throw UnimplementedError();
  }

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
  void invalidateLocalSession() =>
      emit(const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut));

  @override
  Future<void> dispose() => _changes.close();
}
