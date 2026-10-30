import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_order_payment_capability_query.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/buyer_purchase_workspace_repository.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/purchase_request_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/purchase_request_repository.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/presentation/buyer_draft_page.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/presentation/buyer_draft_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

const _membershipId = 'membership-buyer';
const _draftId = 'draft-1';

void main() {
  testWidgets('draft catalog uses server taxonomy options for brand and type', (
    tester,
  ) async {
    final access = _FakeAccess();
    final purchaseRequests = _FakePurchaseRequestRepository();
    final catalog = _RecordingCatalogRepository();
    final model = BuyerDraftViewModel(
      purchaseRequests,
      _FakeWorkspaceRepository(),
      access,
      _FakePaymentCapabilityQuery(),
      _draftId,
    );
    await model.load();

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<BuyerDraftViewModel>.value(value: model),
          Provider<BuyerAccessRepository>.value(value: access),
          Provider<CatalogRepository>.value(value: catalog),
        ],
        child: const MaterialApp(home: BuyerDraftPage()),
      ),
    );
    await tester.tap(find.text('Agregar'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('draft-brand-filter')), findsOneWidget);
    await tester.tap(find.byKey(const Key('draft-brand-filter')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('draft-brand-lookup-search')),
      'Va',
    );
    await tester.pump(const Duration(milliseconds: 350));
    await tester.pumpAndSettle();
    expect(
      catalog.lookups,
      contains(
        predicate(
          (call) =>
              call is Map<String, Object> &&
              call['kind'] == CatalogFilterKind.brand &&
              call['search'] == 'Va' &&
              call['page'] == 0,
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('draft-brand-option-brand-valle')));
    await tester.pumpAndSettle();
    expect(catalog.listCalls.last['brand'], 'Valle');
    expect(catalog.listCalls.last['category'], '');

    await tester.tap(find.byKey(const Key('draft-type-filter')));
    await tester.pumpAndSettle();
    await tester.tap(
      find.byKey(const Key('draft-category-option-category-drinks')),
    );
    await tester.pumpAndSettle();
    expect(catalog.listCalls.last['brand'], 'Valle');
    expect(catalog.listCalls.last['category'], 'Bebidas');

    await tester.pumpWidget(const SizedBox.shrink());
    model.dispose();
    await access.dispose();
  });

  testWidgets('quantity sheet updates the current draft line', (tester) async {
    final access = _FakeAccess();
    final workspace = _FakeWorkspaceRepository();
    final model = BuyerDraftViewModel(
      _FakePurchaseRequestRepository(),
      workspace,
      access,
      const _FakePaymentCapabilityQuery(),
      _draftId,
    );
    await model.load();

    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<BuyerDraftViewModel>.value(value: model),
          Provider<BuyerAccessRepository>.value(value: access),
          Provider<CatalogRepository>.value(
            value: _RecordingCatalogRepository(),
          ),
        ],
        child: const MaterialApp(home: BuyerDraftPage()),
      ),
    );
    await tester.tap(find.text('Cantidad').first);
    await tester.pumpAndSettle();

    expect(find.text('Editar cantidad'), findsOneWidget);
    expect(find.text('Producto de la solicitud'), findsNWidgets(2));
    expect(find.byKey(const Key('quantity-value')), findsOneWidget);
    await tester.tap(find.byKey(const Key('quantity-increment')));
    await tester.pump();
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('quantity-value')))
          .controller
          ?.text,
      '2',
    );
    await tester.tap(find.byKey(const Key('quantity-save')));
    await tester.pumpAndSettle();

    expect(workspace.lastDraftLines?.single.quantity, '2');

    await tester.pumpWidget(const SizedBox.shrink());
    model.dispose();
    await access.dispose();
  });

  test(
    'draft date respects the API floor and allows a later weekend',
    () async {
      final access = _FakeAccess();
      final model = BuyerDraftViewModel(
        _FakePurchaseRequestRepository(),
        _FakeWorkspaceRepository(),
        access,
        const _FakePaymentCapabilityQuery(),
        _draftId,
      );
      await model.load();

      expect(model.minimumDeliveryDate, DateTime(2026, 10, 23));
      expect(model.deliveryDate, DateTime(2026, 10, 23));
      model.selectDate(DateTime(2026, 10, 22));
      expect(model.deliveryDate, DateTime(2026, 10, 23));
      model.selectDate(DateTime(2026, 10, 24));
      expect(model.deliveryDate, DateTime(2026, 10, 24));
      expect(model.hasOutdatedDeliveryDate, isFalse);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'READY_TO_SUBMIT cannot submit when delivery policy is unavailable',
    () async {
      final access = _FakeAccess();
      final repository = _FakePurchaseRequestRepository(
        draftStatus: 'READY_TO_SUBMIT',
        policyUnavailable: true,
      );
      final model = BuyerDraftViewModel(
        repository,
        _FakeWorkspaceRepository(),
        access,
        const _FakePaymentCapabilityQuery(),
        _draftId,
      );
      await model.load();

      expect(model.review?.readyToSubmit, isTrue);
      expect(model.deliveryLeadPolicy, isNull);
      expect(model.canSubmit, isFalse);
      await model.submit();

      expect(repository.submitCalls, 0);
      expect(model.message, contains('plazo mínimo'));

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'READY_TO_SUBMIT cannot submit with a date before the API floor',
    () async {
      final access = _FakeAccess();
      final repository = _FakePurchaseRequestRepository(
        draftStatus: 'READY_TO_SUBMIT',
        requestedDeliveryDate: '2026-10-22',
      );
      final model = BuyerDraftViewModel(
        repository,
        _FakeWorkspaceRepository(),
        access,
        const _FakePaymentCapabilityQuery(),
        _draftId,
      );
      await model.load();

      expect(model.review?.readyToSubmit, isTrue);
      expect(model.hasOutdatedDeliveryDate, isTrue);
      expect(model.canSubmit, isFalse);
      await model.submit();

      expect(repository.submitCalls, 0);
      expect(model.message, contains('fecha mínima'));

      model.dispose();
      await access.dispose();
    },
  );

  test('READY_TO_SUBMIT cannot submit without a delivery date', () async {
    final access = _FakeAccess();
    final repository = _FakePurchaseRequestRepository(
      draftStatus: 'READY_TO_SUBMIT',
      requestedDeliveryDate: null,
    );
    final model = BuyerDraftViewModel(
      repository,
      _FakeWorkspaceRepository(),
      access,
      const _FakePaymentCapabilityQuery(),
      _draftId,
    );
    await model.load();

    expect(model.review?.readyToSubmit, isTrue);
    expect(model.canSubmit, isFalse);
    await model.submit();

    expect(repository.submitCalls, 0);
    expect(model.message, contains('fecha de entrega'));

    model.dispose();
    await access.dispose();
  });
}

final class _RecordingCatalogRepository implements CatalogRepository {
  final listCalls = <Map<String, Object>>[];
  final lookups = <Map<String, Object>>[];

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
    listCalls.add({
      'query': query,
      'page': page,
      'brand': brand,
      'category': category,
    });
    return const CatalogPageProjection(
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 0,
    );
  }

  @override
  Future<CatalogFilterOptionPageProjection> filterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) async {
    lookups.add({'kind': kind, 'search': search, 'page': page});
    final option = switch (kind) {
      CatalogFilterKind.brand => const CatalogFilterOptionProjection(
        id: 'brand-valle',
        name: 'Valle',
      ),
      CatalogFilterKind.category => const CatalogFilterOptionProjection(
        id: 'category-drinks',
        name: 'Bebidas',
      ),
      CatalogFilterKind.family => const CatalogFilterOptionProjection(
        id: 'family-dairy',
        name: 'Lácteos',
      ),
      CatalogFilterKind.variant => const CatalogFilterOptionProjection(
        id: 'variant-skimmed',
        name: 'Descremada',
      ),
    };
    return CatalogFilterOptionPageProjection(
      items: [option],
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
  BuyerAccessSnapshot get snapshot => const BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedIn,
    authorityEpoch: 1,
    currentContext: BuyerAccessContext(
      userId: 'user-buyer',
      membershipId: _membershipId,
      tenantId: 'tenant-buyer',
      tenantName: 'Buyer company',
      tenantSlug: 'buyer-company',
      workspaceId: 'workspace-buyer',
      workspaceName: 'Main',
      workspaceSlug: 'main',
      roles: {'BUYER'},
      permissions: {'buyer.sales.read', 'buyer.sales.write', 'catalog.read'},
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
  void invalidateLocalSession() {}

  @override
  Future<void> dispose() => _changes.close();
}

final class _FakePurchaseRequestRepository
    implements PurchaseRequestRepository {
  _FakePurchaseRequestRepository({
    this.draftStatus = 'DRAFT',
    this.requestedDeliveryDate = '2026-10-23',
    this.policyUnavailable = false,
  });

  final String draftStatus;
  final String? requestedDeliveryDate;
  final bool policyUnavailable;
  int submitCalls = 0;

  @override
  Future<BuyerPurchaseContextProjection> loadBuyerPurchaseContext() async =>
      const BuyerPurchaseContextProjection(
        clientAccountId: 'account-buyer',
        businessName: 'Buyer company',
        paymentCondition: 'CREDIT',
        addresses: [],
      );

  @override
  Future<BuyerDeliveryLeadPolicyProjection> loadDeliveryLeadPolicy() async {
    if (policyUnavailable) {
      throw StateError('Delivery lead policy unavailable');
    }
    return BuyerDeliveryLeadPolicyProjection(
      minimumDeliveryLeadDays: 3,
      earliestAllowedDeliveryDate: DateTime(2026, 10, 23),
      timezone: 'America/Lima',
    );
  }

  @override
  Future<PurchaseRequestDraftProjection> getDraft(String draftId) async =>
      PurchaseRequestDraftProjection(
        id: _draftId,
        clientAccountId: 'account-buyer',
        status: draftStatus,
        version: 1,
        etag: 'etag-1',
        requestedDeliveryDate: requestedDeliveryDate,
        lines: const [
          PurchaseRequestLineProjection(
            skuId: 'sku-existing',
            quantity: '1',
            unit: 'UNIT',
          ),
        ],
      );

  @override
  Future<PurchaseRequestDraftPageProjection> listDrafts({required int page}) =>
      throw UnimplementedError();

  @override
  Future<PurchaseRequestCreationRecord?> creationRecord(String scopeKey) =>
      throw UnimplementedError();

  @override
  Future<void> beginCreation(String scopeKey) => throw UnimplementedError();

  @override
  Future<void> recordCreatedDraft(String scopeKey, String draftId) =>
      throw UnimplementedError();

  @override
  Future<void> clearCreationRecord(String scopeKey) =>
      throw UnimplementedError();

  @override
  Future<PurchaseRequestDraftProjection> createDraft({
    required String clientAccountId,
    required String requestedDeliveryDate,
  }) => throw UnimplementedError();

  @override
  Future<PurchaseRequestDraftProjection> replaceLine({
    required PurchaseRequestDraftProjection draft,
    required String sellableSkuId,
    required num quantity,
    required String unit,
  }) => throw UnimplementedError();

  @override
  Future<PurchaseRequestDraftProjection> setDestination({
    required PurchaseRequestDraftProjection draft,
    required String addressId,
  }) => throw UnimplementedError();

  @override
  Future<PurchaseRequestDraftProjection> previewRoute(
    PurchaseRequestDraftProjection draft,
  ) => throw UnimplementedError();

  @override
  Future<PurchaseRequestDraftProjection> setPreferences({
    required PurchaseRequestDraftProjection draft,
    required String paymentPreference,
    required String requestedDeliveryDate,
  }) => throw UnimplementedError();

  @override
  Future<PurchaseRequestReviewProjection> review(String draftId) async {
    return PurchaseRequestReviewProjection(
      draft: await getDraft(draftId),
      readyToSubmit: true,
      missing: const [],
    );
  }

  @override
  Future<PurchaseRequestDraftProjection> submit(
    PurchaseRequestDraftProjection draft, {
    required String scopeKey,
  }) async {
    submitCalls++;
    return draft;
  }
}

final class _FakeWorkspaceRepository
    implements BuyerPurchaseWorkspaceRepository {
  List<PurchaseRequestLineProjection>? lastDraftLines;

  @override
  Future<BuyerRequestPage> listRequests({required int page, String? status}) =>
      throw UnimplementedError();

  @override
  Future<BuyerRequestProjection> request(String id) =>
      throw UnimplementedError();

  @override
  Future<List<BuyerRequestEvent>> events(String id) =>
      throw UnimplementedError();

  @override
  Future<BuyerMaterialChange?> currentChange(String id) =>
      throw UnimplementedError();

  @override
  Future<BuyerRequestProjection> decide({
    required BuyerRequestProjection request,
    required BuyerRequestDecision decision,
    required String scopeKey,
    BuyerMaterialChange? proposal,
  }) => throw UnimplementedError();

  @override
  Future<PurchaseRequestDraftProjection> replaceDraftLines({
    required PurchaseRequestDraftProjection draft,
    required List<PurchaseRequestLineProjection> lines,
  }) async {
    lastDraftLines = lines;
    return PurchaseRequestDraftProjection(
      id: draft.id,
      clientAccountId: draft.clientAccountId,
      status: draft.status,
      version: draft.version + 1,
      etag: 'etag-${draft.version + 1}',
      lines: lines,
    );
  }
}

final class _FakePaymentCapabilityQuery
    implements BuyerOrderPaymentCapabilityQuery {
  const _FakePaymentCapabilityQuery();

  @override
  Future<bool> isOrderPaymentSupported() async => false;
}
