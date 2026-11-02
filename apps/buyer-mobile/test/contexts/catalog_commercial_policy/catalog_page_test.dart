import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/presentation/catalog_page.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/presentation/catalog_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  testWidgets('catalog controls filter on server and retain filters by page', (
    tester,
  ) async {
    final access = _FakeAccess();
    final repository = _RecordingCatalogRepository();
    final viewModel = CatalogViewModel(repository, access);

    await tester.pumpWidget(
      ChangeNotifierProvider<CatalogViewModel>.value(
        value: viewModel,
        child: const MaterialApp(home: Scaffold(body: CatalogPage())),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('catalog-brand-filter')), findsOneWidget);
    expect(find.byKey(const Key('catalog-type-filter')), findsOneWidget);
    expect(find.byKey(const Key('catalog-category-filter')), findsOneWidget);
    expect(find.byKey(const Key('catalog-family-filter')), findsNothing);
    expect(find.byKey(const Key('catalog-variant-filter')), findsNothing);
    expect(find.text('Todas las marcas'), findsOneWidget);
    expect(find.text('Todas las categorías'), findsOneWidget);
    expect(find.text('Valle'), findsOneWidget);
    expect(find.textContaining('Tipo: Bebidas'), findsOneWidget);
    expect(
      find.textContaining('Familia: Lácteos · Variedad: Descremada'),
      findsOneWidget,
    );
    expect(
      find.textContaining(
        'Marca: Valle · Tipo: Bebidas · Conservación: Refrigerado · '
        'Familia: Lácteos · Variedad: Descremada · Disponible: 12,5 KG',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('Conservación: Refrigerado'), findsOneWidget);
    expect(find.textContaining('Disponible: 12,5 KG'), findsOneWidget);
    expect(find.textContaining('500 g'), findsOneWidget);
    expect(find.text('21 productos'), findsOneWidget);
    expect(find.text('1 de 2 · 21 productos'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('catalog-product-query')),
      'leche',
    );
    await tester.pump(const Duration(milliseconds: 350));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('catalog-brand-filter')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Valle').last);
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('catalog-category-filter')));
    await tester.pumpAndSettle();
    expect(find.text('Bebidas'), findsOneWidget);
    await tester.tap(find.text('Bebidas').last);
    await tester.pumpAndSettle();

    expect(repository.calls.last, {
      'query': 'leche',
      'brand': 'Valle',
      'brandId': '00000000-0000-4000-8000-000000000031',
      'category': 'Bebidas',
      'categoryId': '00000000-0000-4000-8000-000000000032',
      'familyId': '',
      'variantId': '',
      'page': 0,
    });

    await tester.drag(find.byType(CustomScrollView), const Offset(0, -500));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Página siguiente'));
    await tester.pumpAndSettle();

    expect(repository.calls.last, {
      'query': 'leche',
      'brand': 'Valle',
      'brandId': '00000000-0000-4000-8000-000000000031',
      'category': 'Bebidas',
      'categoryId': '00000000-0000-4000-8000-000000000032',
      'familyId': '',
      'variantId': '',
      'page': 1,
    });

    viewModel.dispose();
    await access.dispose();
  });

  testWidgets('uses the singular label for a single catalog result', (
    tester,
  ) async {
    final access = _FakeAccess();
    final repository = _RecordingCatalogRepository()..totalItems = 1;
    final viewModel = CatalogViewModel(repository, access);

    await tester.pumpWidget(
      ChangeNotifierProvider<CatalogViewModel>.value(
        value: viewModel,
        child: const MaterialApp(home: Scaffold(body: CatalogPage())),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('1 producto'), findsOneWidget);
    expect(find.text('1 de 2 · 1 producto'), findsOneWidget);

    await tester.pumpWidget(const SizedBox.shrink());
    viewModel.dispose();
    await access.dispose();
  });

  testWidgets(
    'retained catalog data is marked advisory after refresh failure',
    (tester) async {
      final access = _FakeAccess();
      final repository = _RecordingCatalogRepository();
      final viewModel = CatalogViewModel(repository, access);

      await tester.pumpWidget(
        ChangeNotifierProvider<CatalogViewModel>.value(
          value: viewModel,
          child: const MaterialApp(home: Scaffold(body: CatalogPage())),
        ),
      );
      await tester.pumpAndSettle();
      repository.failNextList = true;
      await viewModel.refresh();
      await tester.pumpAndSettle();

      expect(
        find.textContaining(
          'La información anterior puede estar desactualizada',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('no confirma precio, disponibilidad ni pedido'),
        findsOneWidget,
      );

      await tester.pumpWidget(const SizedBox.shrink());
      viewModel.dispose();
      await access.dispose();
    },
  );
}

final class _RecordingCatalogRepository implements CatalogRepository {
  final calls = <Map<String, Object>>[];
  final lookupCalls = <Map<String, Object?>>[];
  int totalItems = 21;
  bool failNextList = false;

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
    String coldChain = '',
    bool availableOnly = false,
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
    if (failNextList) {
      failNextList = false;
      throw StateError('Fixture unavailable');
    }
    return CatalogPageProjection(
      items: const [
        CatalogItemProjection(
          catalogItemId: 'CAT-0001',
          itemName: 'Leche Valle',
          availabilityStatus: 'AVAILABLE',
          presentation: '500 g',
          brandName: 'Valle',
          categoryName: 'Bebidas',
          coldChainRequirement: 'REFRIGERATED',
          productFamilyName: 'Lácteos',
          productVariantName: 'Descremada',
          unitOfMeasure: 'KG',
          sellableAvailability: 12.5,
        ),
      ],
      page: page,
      size: 20,
      totalItems: totalItems,
      totalPages: 2,
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
    final id = switch (kind) {
      CatalogFilterKind.family => '00000000-0000-4000-8000-000000000021',
      CatalogFilterKind.variant => '00000000-0000-4000-8000-000000000022',
      CatalogFilterKind.brand => '00000000-0000-4000-8000-000000000031',
      CatalogFilterKind.category => '00000000-0000-4000-8000-000000000032',
    };
    final name = switch (kind) {
      CatalogFilterKind.brand => 'Valle',
      CatalogFilterKind.category => 'Bebidas',
      CatalogFilterKind.family => 'Lácteos',
      CatalogFilterKind.variant => 'Descremada',
    };
    return CatalogFilterOptionPageProjection(
      items: [CatalogFilterOptionProjection(id: id, name: name)],
      page: page,
      size: 100,
      total: 1,
    );
  }

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) =>
      throw UnimplementedError();
}

final class _FakeAccess implements BuyerAccessRepository {
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast(sync: true);

  @override
  BuyerAccessSnapshot get snapshot => BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedIn,
    authorityEpoch: 1,
    currentContext: BuyerAccessContext(
      userId: 'user-1',
      membershipId: 'membership-1',
      tenantId: 'tenant-1',
      tenantName: 'Company',
      tenantSlug: 'company',
      workspaceId: 'workspace-1',
      workspaceName: 'Main',
      workspaceSlug: 'main',
      roles: const {'BUYER'},
      permissions: const {'catalog.read'},
    ),
  );

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
