import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_order_payment_capability_query.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/purchase_request_repository.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/purchase_request_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/presentation/purchase_request_composer_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/presentation/purchase_request_composer_page.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _accountId = '00000000-0000-4000-8000-000000000001';
const _membershipId = '00000000-0000-4000-8000-000000000002';
const _addressId = '00000000-0000-4000-8000-000000000003';
const _draftId = '00000000-0000-4000-8000-000000000004';
const _skuId = '00000000-0000-4000-8000-000000000005';
const _scopeKey = '$_membershipId|tenant-1|workspace-1';

void main() {
  test(
    'requires Buyer, payment.read, and server capability for WALLET',
    () async {
      final cases = [
        _snapshot(
          roles: const {'LOGISTICS'},
          permissions: const {
            'buyer.sales.read',
            'buyer.sales.write',
            'payment.read',
          },
        ),
        _snapshot(
          roles: const {'BUYER'},
          permissions: const {'buyer.sales.read', 'buyer.sales.write'},
        ),
      ];

      for (final snapshot in cases) {
        final access = _FakeAccess(snapshot);
        final capability = _FakeWalletCapabilityQuery(supported: true);
        final model = PurchaseRequestComposerViewModel(
          _FakeCatalogRepository(),
          _FakePurchaseRequestRepository(),
          access,
          'CAT-001',
          orderPaymentCapabilityQuery: capability,
        );

        await model.load();
        model.selectPaymentPreference('WALLET');

        expect(capability.calls, 0);
        expect(model.canOfferWalletTender, isFalse);
        expect(model.paymentPreference, 'BANK_TRANSFER');

        model.dispose();
        await access.dispose();
      }
    },
  );

  test(
    'wallet capability outage hides WALLET without blocking other preferences',
    () async {
      final access = _FakeAccess(
        _snapshot(
          roles: const {'BUYER'},
          permissions: const {
            'buyer.sales.read',
            'buyer.sales.write',
            'payment.read',
          },
        ),
      );
      final capability = _FakeWalletCapabilityQuery(
        responses: [
          Future.error(
            const NexaApiFailure(
              code: 'WALLET_UNAVAILABLE',
              statusCode: 503,
              userMessage: 'Billetera no disponible.',
            ),
          ),
        ],
      );
      final repository = _FakePurchaseRequestRepository();
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
        orderPaymentCapabilityQuery: capability,
      );

      await model.load();
      expect(model.status, PurchaseRequestComposerStatus.ready);
      expect(model.canOfferWalletTender, isFalse);
      await model.prepare('1');

      expect(model.status, PurchaseRequestComposerStatus.reviewReady);
      expect(repository.createCount, 1);
      expect(access.invalidations, 0);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'WALLET choice persists only as draft preference before submit',
    () async {
      final access = _FakeAccess(
        _snapshot(
          roles: const {'BUYER'},
          permissions: const {
            'buyer.sales.read',
            'buyer.sales.write',
            'payment.read',
          },
        ),
      );
      final capability = _FakeWalletCapabilityQuery(supported: true);
      final repository = _FakePurchaseRequestRepository();
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
        orderPaymentCapabilityQuery: capability,
      );

      await model.load();
      expect(model.canOfferWalletTender, isTrue);
      model.selectPaymentPreference('WALLET');
      await model.prepare('1');

      expect(model.status, PurchaseRequestComposerStatus.reviewReady);
      expect(repository.preferenceWrites, ['WALLET']);
      expect(repository.submittedCount, 0);
      expect(capability.calls, 2);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'revoked WALLET capability blocks draft writes and clears selection',
    () async {
      final access = _FakeAccess(
        _snapshot(
          roles: const {'BUYER'},
          permissions: const {
            'buyer.sales.read',
            'buyer.sales.write',
            'payment.read',
          },
        ),
      );
      final capability = _FakeWalletCapabilityQuery(
        responses: [Future.value(true), Future.value(false)],
      );
      final repository = _FakePurchaseRequestRepository();
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
        orderPaymentCapabilityQuery: capability,
      );

      await model.load();
      model.selectPaymentPreference('WALLET');
      await model.prepare('1');

      expect(model.status, PurchaseRequestComposerStatus.ready);
      expect(model.paymentPreference, isNull);
      expect(model.review, isNull);
      expect(repository.createCount, 0);
      expect(repository.preferenceWrites, isEmpty);
      expect(repository.submittedCount, 0);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'rechecks WALLET capability after draft setup and before preference write',
    () async {
      final access = _FakeAccess(
        _snapshot(
          roles: const {'BUYER'},
          permissions: const {
            'buyer.sales.read',
            'buyer.sales.write',
            'payment.read',
          },
        ),
      );
      final capability = _FakeWalletCapabilityQuery(
        responses: [
          Future.value(true),
          Future.value(true),
          Future.value(false),
        ],
      );
      final repository = _FakePurchaseRequestRepository();
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
        orderPaymentCapabilityQuery: capability,
      );

      await model.load();
      model.selectPaymentPreference('WALLET');
      await model.prepare('1');

      expect(capability.calls, 3);
      expect(repository.createCount, 1);
      expect(repository.replaceLineCount, 1);
      expect(repository.destinationCount, 1);
      expect(repository.preferenceWrites, isEmpty);
      expect(repository.submittedCount, 0);
      expect(model.draft?.destinationAddressId, _addressId);
      expect(model.draft?.hasWarehouseSelection, isTrue);
      expect(model.paymentPreference, isNull);
      expect(model.review, isNull);
      expect(model.status, PurchaseRequestComposerStatus.ready);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'revoked WALLET capability blocks submit without changing draft',
    () async {
      final access = _FakeAccess(
        _snapshot(
          roles: const {'BUYER'},
          permissions: const {
            'buyer.sales.read',
            'buyer.sales.write',
            'payment.read',
          },
        ),
      );
      final capability = _FakeWalletCapabilityQuery(
        responses: [
          Future.value(true),
          Future.value(true),
          Future.value(false),
        ],
      );
      final repository = _FakePurchaseRequestRepository();
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
        orderPaymentCapabilityQuery: capability,
      );

      await model.load();
      model.selectPaymentPreference('WALLET');
      await model.prepare('1');
      expect(repository.preferenceWrites, ['WALLET']);
      expect(model.status, PurchaseRequestComposerStatus.reviewReady);

      await model.submit();

      expect(model.status, PurchaseRequestComposerStatus.ready);
      expect(model.paymentPreference, isNull);
      expect(model.draft?.paymentPreference, 'WALLET');
      expect(repository.submittedCount, 0);
      expect(repository.preferenceWrites, ['WALLET']);

      model.dispose();
      await access.dispose();
    },
  );

  test('stale wallet capability response cannot cross access lease', () async {
    final access = _FakeAccess(
      _snapshot(
        roles: const {'BUYER'},
        permissions: const {
          'buyer.sales.read',
          'buyer.sales.write',
          'payment.read',
        },
      ),
    );
    final pending = Completer<bool>();
    final capability = _FakeWalletCapabilityQuery(responses: [pending.future]);
    final model = PurchaseRequestComposerViewModel(
      _FakeCatalogRepository(),
      _FakePurchaseRequestRepository(),
      access,
      'CAT-001',
      orderPaymentCapabilityQuery: capability,
    );

    final loading = model.load();
    access.emit(_snapshot(epoch: 2, membershipId: 'membership-next'));
    pending.complete(true);
    await loading;

    expect(model.canOfferWalletTender, isFalse);
    expect(model.item, isNull);
    expect(model.status, PurchaseRequestComposerStatus.unavailable);

    model.dispose();
    await access.dispose();
  });

  test(
    'server review gates submit and submitted state is server returned',
    () async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakePurchaseRequestRepository();
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await model.load();
      expect(model.status, PurchaseRequestComposerStatus.ready);
      expect(model.selectedAddressId, _addressId);

      await model.prepare('2');
      expect(model.status, PurchaseRequestComposerStatus.reviewReady);
      expect(model.review?.readyToSubmit, isTrue);
      expect(repository.createdAccountId, _accountId);
      expect(repository.submittedCount, 0);

      await model.submit();
      expect(model.status, PurchaseRequestComposerStatus.submitted);
      expect(model.draft?.status, 'SUBMITTED');
      expect(repository.submittedCount, 1);
      expect(repository.submitScopeKey, '$_membershipId|tenant-1|workspace-1');

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'a stale checkout load cannot publish account data after context change',
    () async {
      final access = _FakeAccess(_snapshot());
      final pending = Completer<BuyerPurchaseContextProjection>();
      final repository = _FakePurchaseRequestRepository(
        contextResult: pending.future,
      );
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      final loading = model.load();
      access.emit(_snapshot(epoch: 2, membershipId: 'membership-2'));
      pending.complete(_purchaseContext(membershipId: _membershipId));
      await loading;

      expect(model.item, isNull);
      expect(model.purchaseContext, isNull);
      expect(model.status, PurchaseRequestComposerStatus.unavailable);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'profile membership mismatch fails closed without creating a draft',
    () async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakePurchaseRequestRepository(
        contextResult: Future.value(
          _purchaseContext(membershipId: 'membership-other'),
        ),
      );
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await model.load();

      expect(model.status, PurchaseRequestComposerStatus.unavailable);
      expect(model.message, contains('no coincide'));
      expect(repository.createCount, 0);
      expect(access.snapshot.isSignedIn, isTrue);

      model.dispose();
      await access.dispose();
    },
  );

  test('403 does not invalidate a valid Buyer session', () async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakePurchaseRequestRepository(
      contextError: const NexaApiFailure(
        code: 'permission_denied',
        statusCode: 403,
        userMessage:
            'This information is not available for your current access.',
      ),
    );
    final model = PurchaseRequestComposerViewModel(
      _FakeCatalogRepository(),
      repository,
      access,
      'CAT-001',
    );
    await model.load();

    expect(model.status, PurchaseRequestComposerStatus.unavailable);
    expect(access.snapshot.isSignedIn, isTrue);
    expect(access.invalidations, 0);

    model.dispose();
    await access.dispose();
  });

  test(
    'a draft response arriving after authority change is discarded',
    () async {
      final access = _FakeAccess(_snapshot());
      final pending = Completer<PurchaseRequestDraftProjection>();
      final repository = _FakePurchaseRequestRepository(
        createResult: pending.future,
      );
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await model.load();
      final preparing = model.prepare('1');

      access.emit(_snapshot(epoch: 2, membershipId: 'membership-2'));
      pending.complete(_draft());
      await preparing;

      expect(model.draft, isNull);
      expect(model.status, PurchaseRequestComposerStatus.unavailable);
      expect(repository.replaceLineCount, 0);
      expect(repository.destinationCount, 0);

      model.dispose();
      await access.dispose();
    },
  );

  test(
    'ambiguous draft creation stays blocked after reopening when list is empty',
    () async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakePurchaseRequestRepository(
        createError: const NexaApiFailure(
          code: 'network_timeout',
          userMessage: 'No se confirmó la respuesta.',
        ),
      );
      final first = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await first.load();
      await first.prepare('1');

      expect(repository.createCount, 1);
      expect(
        first.recoveryStatus,
        PurchaseRequestRecoveryStatus.reviewRequired,
      );
      expect(first.recoveryCandidates, isEmpty);
      expect(first.canPrepareRequest, isFalse);
      expect(repository.creationRecords[_creationIntentKey], isNotNull);
      expect(repository.creationRecords[_creationIntentKey]!.isUnknown, isTrue);

      first.dispose();
      final reopened = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await reopened.load();
      await reopened.prepare('1');

      expect(reopened.recoveryCandidates, isEmpty);
      expect(reopened.canPrepareRequest, isFalse);
      expect(repository.createCount, 1);
      expect(repository.listedPages, [0, 0]);

      reopened.dispose();
      await access.dispose();
    },
  );

  test('explicitly selected server draft is fetched and resumed', () async {
    final access = _FakeAccess(_snapshot());
    final candidate = _draft();
    final repository = _FakePurchaseRequestRepository(
      initialCreationRecord: const PurchaseRequestCreationRecord.unknown(),
      drafts: {candidate.id: candidate},
      listPages: {
        0: Future.value(_draftPage([candidate])),
      },
    );
    final model = PurchaseRequestComposerViewModel(
      _FakeCatalogRepository(),
      repository,
      access,
      'CAT-001',
    );
    await model.load();
    expect(model.recoveryStatus, PurchaseRequestRecoveryStatus.reviewRequired);
    expect(model.recoveryCandidates, hasLength(1));
    expect(repository.createCount, 0);

    await model.resumeDraft(candidate.id);

    expect(repository.getDraftCount, 1);
    expect(repository.creationRecordReads, 1);
    expect(
      repository.creationRecords[_creationIntentKey]?.draftId,
      candidate.id,
    );
    expect(model.draft?.id, candidate.id);
    expect(model.recoveryStatus, PurchaseRequestRecoveryStatus.none);
    expect(model.status, PurchaseRequestComposerStatus.reviewReady);
    expect(repository.createCount, 0);

    model.dispose();
    await access.dispose();
  });

  test(
    'recovery pages remain user selected and stale page results are purged',
    () async {
      final access = _FakeAccess(_snapshot());
      final pending = Completer<PurchaseRequestDraftPageProjection>();
      final firstDraft = _draft();
      final secondDraft = _draft(version: 1, status: 'DRAFT');
      final repository = _FakePurchaseRequestRepository(
        initialCreationRecord: const PurchaseRequestCreationRecord.unknown(),
        listPages: {
          0: Future.value(_draftPage([firstDraft], totalPages: 2)),
          1: pending.future,
        },
        drafts: {firstDraft.id: firstDraft, secondDraft.id: secondDraft},
      );
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await model.load();
      expect(model.recoveryCandidates.single.id, firstDraft.id);

      final loadingNextPage = model.refreshRecovery(page: 1);
      await Future<void>.delayed(Duration.zero);
      expect(repository.listedPages, [0, 1]);
      access.emit(_snapshot(epoch: 2, membershipId: 'membership-2'));
      pending.complete(_draftPage([secondDraft], page: 1, totalPages: 2));
      await loadingNextPage;

      expect(model.item, isNull);
      expect(model.recoveryCandidates, isEmpty);
      expect(model.inspectedRecoveryDraft, isNull);
      expect(model.status, PurchaseRequestComposerStatus.unavailable);
      expect(model.recoveryStatus, PurchaseRequestRecoveryStatus.none);
      expect(repository.createCount, 0);

      model.dispose();
      await access.dispose();
    },
  );

  test('storage write failure blocks draft creation before POST', () async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakePurchaseRequestRepository(
      beginCreationError: const FileSystemException('disk full'),
    );
    final model = PurchaseRequestComposerViewModel(
      _FakeCatalogRepository(),
      repository,
      access,
      'CAT-001',
    );
    await model.load();
    await model.prepare('1');

    expect(repository.createCount, 0);
    expect(model.canPrepareRequest, isFalse);
    expect(model.recoveryStatus, PurchaseRequestRecoveryStatus.unavailable);

    model.dispose();
    await access.dispose();
  });

  testWidgets('composer shows the Buyer summary and review action', (
    tester,
  ) async {
    final access = _FakeAccess(_snapshot());
    final model = PurchaseRequestComposerViewModel(
      _FakeCatalogRepository(),
      _FakePurchaseRequestRepository(),
      access,
      'CAT-001',
    );
    await model.load();

    await tester.pumpWidget(
      ChangeNotifierProvider<PurchaseRequestComposerViewModel>.value(
        value: model,
        child: const MaterialApp(home: PurchaseRequestComposerPage()),
      ),
    );

    expect(find.text('Producto Buyer'), findsOneWidget);
    expect(find.text('Cuenta Buyer'), findsOneWidget);
    expect(find.text('Preparar revisión'), findsOneWidget);
    expect(find.text('Transferencia bancaria'), findsOneWidget);
    expect(find.text('Billetera'), findsNothing);
    expect(find.text('Enviar solicitud'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
    model.dispose();
    await access.dispose();
  });

  testWidgets('WALLET copy says preference, not payment confirmation', (
    tester,
  ) async {
    final access = _FakeAccess(
      _snapshot(
        roles: const {'BUYER'},
        permissions: const {
          'buyer.sales.read',
          'buyer.sales.write',
          'payment.read',
        },
      ),
    );
    final model = PurchaseRequestComposerViewModel(
      _FakeCatalogRepository(),
      _FakePurchaseRequestRepository(),
      access,
      'CAT-001',
      orderPaymentCapabilityQuery: _FakeWalletCapabilityQuery(supported: true),
    );
    await model.load();
    model.selectPaymentPreference('WALLET');

    await tester.pumpWidget(
      ChangeNotifierProvider<PurchaseRequestComposerViewModel>.value(
        value: model,
        child: const MaterialApp(home: PurchaseRequestComposerPage()),
      ),
    );

    expect(find.text('Billetera'), findsOneWidget);
    expect(
      find.textContaining('no reserva saldo ni confirma el pago'),
      findsOneWidget,
    );
    expect(find.textContaining('Pago exitoso'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
    model.dispose();
    await access.dispose();
  });

  testWidgets(
    'uncertain create recovery shows choices without prepare action',
    (tester) async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakePurchaseRequestRepository(
        initialCreationRecord: const PurchaseRequestCreationRecord.unknown(),
      );
      final model = PurchaseRequestComposerViewModel(
        _FakeCatalogRepository(),
        repository,
        access,
        'CAT-001',
      );
      await model.load();

      await tester.pumpWidget(
        ChangeNotifierProvider<PurchaseRequestComposerViewModel>.value(
          value: model,
          child: const MaterialApp(home: PurchaseRequestComposerPage()),
        ),
      );

      expect(find.text('Revisar solicitud previa'), findsOneWidget);
      expect(find.textContaining('lista vacía'), findsOneWidget);
      expect(find.text('Actualizar borradores'), findsOneWidget);
      expect(
        find.text('Resolver y considerar una solicitud nueva'),
        findsOneWidget,
      );
      expect(find.text('Preparar revisión'), findsNothing);
      expect(repository.createCount, 0);

      await tester.pumpWidget(const SizedBox.shrink());
      model.dispose();
      await access.dispose();
    },
  );
}

BuyerAccessSnapshot _snapshot({
  int epoch = 1,
  String membershipId = _membershipId,
  Set<String> roles = const {},
  Set<String> permissions = const {'buyer.sales.read', 'buyer.sales.write'},
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    membershipId: membershipId,
    tenantId: 'tenant-1',
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: 'workspace-1',
    workspaceName: 'Main',
    workspaceSlug: 'main',
    roles: roles,
    permissions: permissions,
  ),
);

BuyerPurchaseContextProjection _purchaseContext({
  String membershipId = _membershipId,
}) => BuyerPurchaseContextProjection(
  clientAccountId: _accountId,
  buyerMembershipId: membershipId,
  businessName: 'Cuenta Buyer',
  paymentCondition: 'BANK_TRANSFER',
  addresses: const [
    BuyerDeliveryAddressProjection(
      id: _addressId,
      label: 'Principal',
      line: 'Av. Central 100',
      active: true,
      defaultAddress: true,
      latitude: -12.1,
      longitude: -77.1,
    ),
  ],
);

PurchaseRequestDraftProjection _draft({
  int version = 0,
  String status = 'DRAFT',
  List<PurchaseRequestLineProjection> lines = const [],
  String? destinationAddressId,
  String? routeProvider,
  bool hasWarehouseSelection = false,
  String? paymentPreference,
  String? requestedDeliveryDate,
}) => PurchaseRequestDraftProjection(
  id: _draftId,
  clientAccountId: _accountId,
  status: status,
  version: version,
  etag: '"$version"',
  lines: lines,
  destinationAddressId: destinationAddressId,
  routeProvider: routeProvider,
  hasWarehouseSelection: hasWarehouseSelection,
  paymentPreference: paymentPreference,
  requestedDeliveryDate: requestedDeliveryDate,
);

final class _FakeCatalogRepository implements CatalogRepository {
  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
  }) => throw UnimplementedError();

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) async =>
      const CatalogItemProjection(
        catalogItemId: 'CAT-001',
        sellableSkuId: _skuId,
        itemName: 'Producto Buyer',
        availabilityStatus: 'AVAILABLE',
        unitOfMeasure: 'BOX',
      );
}

final class _FakeWalletCapabilityQuery
    implements BuyerOrderPaymentCapabilityQuery {
  _FakeWalletCapabilityQuery({
    this.supported = false,
    List<Future<bool>> responses = const [],
  }) : _responses = [...responses];

  final List<Future<bool>> _responses;
  bool supported;
  int calls = 0;

  @override
  Future<bool> isOrderPaymentSupported() {
    calls++;
    if (_responses.isNotEmpty) return _responses.removeAt(0);
    return Future.value(supported);
  }
}

const _creationIntentKey = '$_scopeKey|purchase-request-create|$_skuId';

PurchaseRequestDraftPageProjection _draftPage(
  List<PurchaseRequestDraftProjection> drafts, {
  int page = 0,
  int totalPages = 1,
}) => PurchaseRequestDraftPageProjection(
  items: drafts
      .map(
        (draft) => PurchaseRequestDraftSummaryProjection(
          id: draft.id,
          status: draft.status,
          version: draft.version,
          lineCount: draft.lines.length,
          requestedDeliveryDate: draft.requestedDeliveryDate,
          createdAt: DateTime.utc(2026, 10, 9),
          updatedAt: DateTime.utc(2026, 10, 9),
        ),
      )
      .toList(growable: false),
  page: page,
  size: 20,
  totalItems: drafts.length,
  totalPages: totalPages,
);

final class _FakePurchaseRequestRepository
    implements PurchaseRequestRepository {
  _FakePurchaseRequestRepository({
    this.contextResult,
    this.contextError,
    this.createResult,
    this.createError,
    this.beginCreationError,
    this.initialCreationRecord,
    this.listPages = const {},
    this.drafts = const {},
  }) : _creationRecord = initialCreationRecord;

  final Future<BuyerPurchaseContextProjection>? contextResult;
  final Object? contextError;
  final Future<PurchaseRequestDraftProjection>? createResult;
  final Object? createError;
  final Object? beginCreationError;
  final PurchaseRequestCreationRecord? initialCreationRecord;
  final Map<int, Future<PurchaseRequestDraftPageProjection>> listPages;
  final Map<String, PurchaseRequestDraftProjection> drafts;
  PurchaseRequestCreationRecord? _creationRecord;
  final creationRecords = <String, PurchaseRequestCreationRecord?>{};
  final listedPages = <int>[];
  int creationRecordReads = 0;
  int getDraftCount = 0;
  String? createdAccountId;
  int createCount = 0;
  int replaceLineCount = 0;
  int destinationCount = 0;
  int submittedCount = 0;
  String? submitScopeKey;
  final preferenceWrites = <String>[];
  PurchaseRequestDraftProjection current = _draft();

  @override
  Future<BuyerPurchaseContextProjection> loadBuyerPurchaseContext() {
    if (contextError != null) return Future.error(contextError!);
    return contextResult ?? Future.value(_purchaseContext());
  }

  @override
  Future<PurchaseRequestDraftPageProjection> listDrafts({required int page}) {
    listedPages.add(page);
    return listPages[page] ?? Future.value(_draftPage(const []));
  }

  @override
  Future<PurchaseRequestCreationRecord?> creationRecord(String scopeKey) async {
    creationRecordReads++;
    if (creationRecords.containsKey(scopeKey)) {
      return creationRecords[scopeKey];
    }
    return _creationRecord;
  }

  @override
  Future<void> beginCreation(String scopeKey) async {
    if (beginCreationError case final error?) throw error;
    if (_creationRecord != null || creationRecords.containsKey(scopeKey)) {
      throw StateError('Creation record already exists.');
    }
    _creationRecord = const PurchaseRequestCreationRecord.unknown();
    creationRecords[scopeKey] = _creationRecord;
  }

  @override
  Future<void> recordCreatedDraft(String scopeKey, String draftId) async {
    final value = PurchaseRequestCreationRecord.known(draftId);
    _creationRecord = value;
    creationRecords[scopeKey] = value;
  }

  @override
  Future<void> clearCreationRecord(String scopeKey) async {
    _creationRecord = null;
    creationRecords.remove(scopeKey);
  }

  @override
  Future<PurchaseRequestDraftProjection> createDraft({
    required String clientAccountId,
    required String requestedDeliveryDate,
  }) {
    createCount++;
    createdAccountId = clientAccountId;
    if (createError case final error?) return Future.error(error);
    if (createResult != null) return createResult!;
    current = _draft(requestedDeliveryDate: requestedDeliveryDate);
    return Future.value(current);
  }

  @override
  Future<PurchaseRequestDraftProjection> getDraft(String draftId) async {
    getDraftCount++;
    return drafts[draftId] ?? current;
  }

  @override
  Future<PurchaseRequestDraftProjection> replaceLine({
    required PurchaseRequestDraftProjection draft,
    required String sellableSkuId,
    required num quantity,
    required String unit,
  }) async {
    replaceLineCount++;
    current = _draft(
      version: draft.version + 1,
      requestedDeliveryDate: draft.requestedDeliveryDate,
      lines: [
        PurchaseRequestLineProjection(
          skuId: sellableSkuId,
          quantity: quantity.toString(),
          unit: unit,
        ),
      ],
    );
    return current;
  }

  @override
  Future<PurchaseRequestDraftProjection> setDestination({
    required PurchaseRequestDraftProjection draft,
    required String addressId,
  }) async {
    destinationCount++;
    current = _draft(
      version: draft.version + 1,
      lines: draft.lines,
      destinationAddressId: addressId,
      requestedDeliveryDate: draft.requestedDeliveryDate,
    );
    return current;
  }

  @override
  Future<PurchaseRequestDraftProjection> previewRoute(
    PurchaseRequestDraftProjection draft,
  ) async {
    current = _draft(
      version: draft.version + 1,
      lines: draft.lines,
      destinationAddressId: draft.destinationAddressId,
      routeProvider: 'LOCAL_ESTIMATE',
      hasWarehouseSelection: true,
      requestedDeliveryDate: draft.requestedDeliveryDate,
    );
    return current;
  }

  @override
  Future<PurchaseRequestDraftProjection> setPreferences({
    required PurchaseRequestDraftProjection draft,
    required String paymentPreference,
    required String requestedDeliveryDate,
  }) async {
    preferenceWrites.add(paymentPreference);
    current = _draft(
      version: draft.version + 1,
      lines: draft.lines,
      destinationAddressId: draft.destinationAddressId,
      routeProvider: draft.routeProvider,
      hasWarehouseSelection: draft.hasWarehouseSelection,
      paymentPreference: paymentPreference,
      requestedDeliveryDate: requestedDeliveryDate,
    );
    return current;
  }

  @override
  Future<PurchaseRequestReviewProjection> review(String draftId) async =>
      PurchaseRequestReviewProjection(
        draft: current,
        readyToSubmit: true,
        missing: const [],
      );

  @override
  Future<PurchaseRequestDraftProjection> submit(
    PurchaseRequestDraftProjection draft, {
    required String scopeKey,
  }) async {
    submittedCount++;
    submitScopeKey = scopeKey;
    current = _draft(
      version: draft.version + 1,
      status: 'SUBMITTED',
      lines: draft.lines,
      destinationAddressId: draft.destinationAddressId,
      routeProvider: draft.routeProvider,
      hasWarehouseSelection: draft.hasWarehouseSelection,
      paymentPreference: draft.paymentPreference,
      requestedDeliveryDate: draft.requestedDeliveryDate,
    );
    return current;
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

  void emit(BuyerAccessSnapshot value) {
    _snapshot = value;
    _changes.add(value);
  }

  @override
  void invalidateLocalSession() => invalidations++;

  @override
  Future<void> signIn({required String identifier, required String password}) =>
      throw UnimplementedError();

  @override
  Future<void> selectContext(String membershipId) => throw UnimplementedError();

  @override
  Future<void> signOut() => throw UnimplementedError();

  @override
  Future<void> dispose() => _changes.close();
}
