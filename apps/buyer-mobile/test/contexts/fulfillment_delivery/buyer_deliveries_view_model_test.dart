import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_deliveries_repository.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_receipt_evidence_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_receipt_idempotency_store.dart';
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

      pendingNextPage.complete(_page(1, 26, salesOrderNumber: 'OLD-CONTEXT'));
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
      receiptStore: _ReceiptStore(),
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
        receiptStore: _ReceiptStore(),
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
        receiptStore: _ReceiptStore(),
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
      receiptStore: _ReceiptStore(),
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
        receiptStore: _ReceiptStore(),
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
        receiptStore: _ReceiptStore(),
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

  test('handoff verification is separate from receipt creation', () async {
    final access = _FakeAccess(
      _signedInSnapshot(
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    final repository = _Repository((_) async => _page(0, 1));
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();

    await viewModel.validateHandoff('  buyer-code  ');

    expect(repository.validationCalls, 1);
    expect(repository.lastValidatedToken, 'buyer-code');
    expect(viewModel.handoffStatus, BuyerHandoffStatus.verified);
    expect(viewModel.receiptStatus, BuyerReceiptStatus.idle);
    expect(repository.receiptCalls, 0);
    expect(viewModel.canSubmitReceipt, isTrue);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'Buyer QR acceptance reports server-confirmed order completion',
    () async {
      final access = _FakeAccess(
        _signedInSnapshot(
          permissions: const {'buyer.tracking.read', 'buyer.order.read'},
        ),
      );
      final repository = _Repository((_) async => _page(0, 1))
        ..acceptanceResult = Future.value(_acceptance(orderCompleted: true));
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
        receiptStore: _ReceiptStore(),
      );
      await viewModel.load();

      await viewModel.acceptHandoff('  buyer-code  ');

      expect(repository.acceptanceCalls, 1);
      expect(repository.validationCalls, 0);
      expect(repository.receiptCalls, 0);
      expect(repository.acceptanceCommands.single['token'], 'buyer-code');
      expect(
        repository.acceptanceCommands.single['expectedDeliveryId'],
        _deliveryId,
      );
      expect(
        repository.acceptanceCommands.single['idempotencyKey'],
        isA<String>(),
      );
      expect(viewModel.handoffAcceptance?.orderCompleted, isTrue);
      expect(viewModel.handoffStatus, BuyerHandoffStatus.verified);
      expect(viewModel.receiptStatus, BuyerReceiptStatus.accepted);
      expect(
        viewModel.receiptMessage,
        'Recepción confirmada. El pedido está completo.',
      );

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('ambiguous QR retry reuses same idempotent command', () async {
    final access = _FakeAccess(
      _signedInSnapshot(
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    final response = Completer<BuyerReceiptAcceptanceProjection>();
    final repository = _Repository((_) async => _page(0, 1))
      ..acceptanceResult = response.future;
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();

    final firstAttempt = viewModel.acceptHandoff('buyer-code');
    response.completeError(Exception('connection lost'));
    await firstAttempt;

    expect(viewModel.receiptStatus, BuyerReceiptStatus.unavailable);
    expect(viewModel.canRetryHandoffAcceptance, isTrue);
    repository.acceptanceResult = Future.value(
      _acceptance(orderCompleted: false, replayed: true),
    );
    await viewModel.retryHandoffAcceptance();

    expect(repository.acceptanceCommands, hasLength(2));
    expect(repository.acceptanceCommands[1], repository.acceptanceCommands[0]);
    expect(viewModel.handoffAcceptance?.replayed, isTrue);
    expect(viewModel.handoffAcceptance?.orderCompleted, isFalse);
    expect(
      viewModel.receiptMessage,
      'Recepción confirmada. El pedido sigue pendiente de completar.',
    );

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'attaches evidence to persisted disputed receipt after delivery ends',
    () async {
      final access = _FakeAccess(
        _signedInSnapshot(
          permissions: const {
            'buyer.tracking.read',
            'buyer.receipt.evidence.attach',
          },
        ),
      );
      final disputed = _receipt(decision: 'DISPUTED', reason: 'Caja faltante');
      final repository = _Repository((_) async => _page(0, 1))
        ..detailResult = Future.value(
          BuyerDeliveryDetailProjection(
            delivery: _delivery('SO-0001', status: 'COMPLETED'),
            events: const [],
            receipts: [disputed],
          ),
        );
      final evidenceStore = _EvidenceStore();
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
        receiptStore: _ReceiptStore(),
        evidenceStore: evidenceStore,
      );
      await viewModel.load();

      expect(viewModel.canAttachEvidenceTo(disputed), isTrue);
      await viewModel.uploadReceiptEvidence(
        receiptId: disputed.id,
        filename: 'receipt.png',
        contentType: 'image/png',
        bytes: [1, 2, 3],
      );

      expect(repository.evidenceUploadCalls, 1);
      expect(
        repository.evidenceUploadCommands.single['receiptId'],
        disputed.id,
      );
      expect(
        repository.evidenceUploadCommands.single['idempotencyKey'],
        evidenceStore.keys.values.single,
      );
      expect(
        viewModel.receipts.single.evidence.single.lifecycleStatus,
        'QUARANTINED',
      );
      expect(viewModel.evidenceStatus, BuyerReceiptEvidenceStatus.uploaded);
      expect(viewModel.evidenceMessage, contains('estado del análisis'));

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('evidence attach fails closed without narrow Buyer grant', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final disputed = _receipt(decision: 'DISPUTED', reason: 'Caja faltante');
    final repository = _Repository((_) async => _page(0, 1))
      ..detailResult = Future.value(
        BuyerDeliveryDetailProjection(
          delivery: _delivery('SO-0001', status: 'COMPLETED'),
          events: const [],
          receipts: [disputed],
        ),
      );
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
      evidenceStore: _EvidenceStore(),
    );
    await viewModel.load();

    await viewModel.uploadReceiptEvidence(
      receiptId: disputed.id,
      filename: 'receipt.png',
      contentType: 'image/png',
      bytes: [1, 2, 3],
    );

    expect(viewModel.canAttachEvidenceTo(disputed), isFalse);
    expect(
      viewModel.evidenceStatus,
      BuyerReceiptEvidenceStatus.permissionDenied,
    );
    expect(repository.evidenceUploadCalls, 0);
    expect(access.snapshot.isSignedIn, isTrue);

    viewModel.dispose();
    await access.dispose();
  });

  test('handoff for a different delivery cannot authorize a receipt', () async {
    const anotherDelivery = '00000000-0000-4000-8000-000000000099';
    final access = _FakeAccess(
      _signedInSnapshot(
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    final repository = _Repository((_) async => _page(0, 1))
      ..validationResult = Future.value(_handoff(deliveryId: anotherDelivery));
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();

    await viewModel.validateHandoff('buyer-code');
    await viewModel.submitReceipt(decision: 'ACCEPTED', acceptedQuantity: '3');

    expect(viewModel.handoffStatus, BuyerHandoffStatus.invalid);
    expect(viewModel.canSubmitReceipt, isFalse);
    expect(repository.receiptCalls, 0);

    viewModel.dispose();
    await access.dispose();
  });

  test('disputed receipt requires its own Buyer grant and a reason', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final repository = _Repository((_) async => _page(0, 1));
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();
    await viewModel.validateHandoff('buyer-code');

    await viewModel.submitReceipt(
      decision: 'DISPUTED',
      acceptedQuantity: '2',
      reason: '   ',
    );
    expect(viewModel.receiptStatus, BuyerReceiptStatus.permissionDenied);
    expect(repository.receiptCalls, 0);

    access.emit(
      _signedInSnapshot(
        epoch: 2,
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    expect(viewModel.delivery, isNull);
    expect(viewModel.handoff, isNull);
    expect(viewModel.authorityRevision, 1);

    viewModel.dispose();
    await access.dispose();
  });

  test('disputed receipt rejects a missing reason before sending', () async {
    final access = _FakeAccess(
      _signedInSnapshot(
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    final repository = _Repository((_) async => _page(0, 1));
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();
    await viewModel.validateHandoff('buyer-code');

    await viewModel.submitReceipt(decision: 'DISPUTED', acceptedQuantity: '2');

    expect(viewModel.receiptStatus, BuyerReceiptStatus.conflict);
    expect(viewModel.receiptMessage, contains('Explica la diferencia'));
    expect(repository.receiptCalls, 0);

    viewModel.dispose();
    await access.dispose();
  });

  test('uncertain receipt failure retries exact key and payload', () async {
    final access = _FakeAccess(
      _signedInSnapshot(
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    final response = Completer<BuyerReceiptProjection>();
    final repository = _Repository((_) async => _page(0, 1))
      ..receiptResult = response.future;
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();
    await viewModel.validateHandoff('buyer-code');
    final submission = viewModel.submitReceipt(
      decision: 'DISPUTED',
      acceptedQuantity: '2',
      reason: 'One carton missing',
    );
    await Future<void>.delayed(Duration.zero);
    response.completeError(Exception('connection lost'));
    await submission;

    expect(viewModel.receiptStatus, BuyerReceiptStatus.unavailable);
    expect(viewModel.canRetryReceipt, isTrue);
    repository.receiptResult = Future.value(
      _receipt(
        decision: 'DISPUTED',
        acceptedQuantity: 2,
        reason: 'One carton missing',
        replayed: true,
      ),
    );
    await viewModel.retryReceipt();

    expect(repository.receiptCommands, hasLength(2));
    expect(repository.receiptCommands[1], repository.receiptCommands[0]);
    expect(viewModel.receiptStatus, BuyerReceiptStatus.disputed);
    expect(viewModel.receipt?.replayed, isTrue);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'receipt conflict unlocks only after a fresh handoff verification',
    () async {
      final access = _FakeAccess(
        _signedInSnapshot(
          permissions: const {'buyer.tracking.read', 'buyer.order.read'},
        ),
      );
      final receiptConflict = Completer<BuyerReceiptProjection>();
      final repository = _Repository((_) async => _page(0, 1))
        ..receiptResult = receiptConflict.future;
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
        receiptStore: _ReceiptStore(),
      );
      await viewModel.load();
      await viewModel.validateHandoff('buyer-code');

      final submitting = viewModel.submitReceipt(
        decision: 'ACCEPTED',
        acceptedQuantity: '3',
      );
      receiptConflict.completeError(
        const NexaApiFailure(
          code: 'stale_attempt',
          statusCode: 409,
          userMessage: 'Attempt already has a receipt.',
        ),
      );
      await submitting;

      expect(viewModel.receiptStatus, BuyerReceiptStatus.conflict);
      expect(viewModel.receiptAttemptLocked, isFalse);
      expect(viewModel.canRetryReceipt, isFalse);
      expect(viewModel.handoff, isNull);
      expect(viewModel.receiptMessage, contains('Verifica de nuevo'));

      await viewModel.validateHandoff('buyer-code');
      expect(repository.validationCalls, 2);
      expect(viewModel.handoffStatus, BuyerHandoffStatus.verified);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('scope change discards a pending receipt response', () async {
    final access = _FakeAccess(
      _signedInSnapshot(
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    final response = Completer<BuyerReceiptProjection>();
    final repository = _Repository((_) async => _page(0, 1))
      ..receiptResult = response.future;
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();
    await viewModel.validateHandoff('buyer-code');

    final pending = viewModel.submitReceipt(
      decision: 'ACCEPTED',
      acceptedQuantity: '3',
    );
    access.emit(
      _signedInSnapshot(
        epoch: 2,
        tenantId: 'tenant-new',
        permissions: const {'buyer.tracking.read', 'buyer.order.read'},
      ),
    );
    response.complete(_receipt());
    await pending;

    expect(viewModel.delivery, isNull);
    expect(viewModel.handoff, isNull);
    expect(viewModel.receipt, isNull);
    expect(viewModel.receiptStatus, BuyerReceiptStatus.idle);

    viewModel.dispose();
    await access.dispose();
  });

  test('live location is fetched only for an active delivery', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final repository = _Repository((_) async => _page(0, 1));
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );
    await viewModel.load();
    await Future<void>.delayed(Duration.zero);

    expect(repository.locationCalls, 1);
    expect(
      viewModel.currentLocation?.sampleId,
      '00000000-0000-4000-8000-000000000006',
    );

    repository.detailResult = Future.value(_detail(status: 'PARTIAL'));
    await viewModel.load();

    expect(repository.locationCalls, 1);
    expect(viewModel.currentLocation, isNull);
    expect(viewModel.locationStatus, BuyerLiveLocationStatus.inactive);

    viewModel.dispose();
    await access.dispose();
  });

  test('live location is hidden after its API expiry time', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final repository = _Repository((_) async => _page(0, 1))
      ..locationResult = Future.value(_tracking(expired: true));
    final viewModel = BuyerDeliveryDetailViewModel(
      repository,
      access,
      _deliveryId,
      receiptStore: _ReceiptStore(),
    );

    await viewModel.load();
    await Future<void>.delayed(Duration.zero);

    expect(viewModel.currentLocation, isNull);
    expect(viewModel.locationStatus, BuyerLiveLocationStatus.stale);
    expect(repository.locationCalls, 1);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'location 403 and 404 clear coordinates without changing authority',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final repository = _Repository((_) async => _page(0, 1));
      final viewModel = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
        receiptStore: _ReceiptStore(),
      );
      await viewModel.load();
      await Future<void>.delayed(Duration.zero);
      expect(viewModel.currentLocation, isNotNull);

      repository.locationResult = Future.error(
        const NexaApiFailure(
          code: 'permission_denied',
          statusCode: 403,
          userMessage: 'Not permitted.',
        ),
      );
      await viewModel.refreshLocation();
      expect(viewModel.currentLocation, isNull);
      expect(
        viewModel.locationStatus,
        BuyerLiveLocationStatus.permissionDenied,
      );
      expect(access.snapshot.isSignedIn, isTrue);

      repository.locationResult = Future.error(
        const NexaApiFailure(
          code: 'location_inactive',
          statusCode: 404,
          userMessage: 'No active location.',
        ),
      );
      await viewModel.refreshLocation();
      expect(viewModel.currentLocation, isNull);
      expect(viewModel.locationStatus, BuyerLiveLocationStatus.inactive);
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
  String salesOrderNumber = 'SO-0001',
}) => BuyerDeliveryPageProjection(
  items: [_delivery(salesOrderNumber)],
  page: page,
  size: 25,
  total: total,
);

BuyerDeliveryProjection _delivery(
  String salesOrderNumber, {
  String status = 'DISPATCHED',
}) => BuyerDeliveryProjection(
  id: _deliveryId,
  salesOrderNumber: salesOrderNumber,
  status: status,
  version: 1,
  createdAt: DateTime.utc(2026, 10, 9),
  updatedAt: DateTime.utc(2026, 10, 9),
  destination: 'Sucursal principal',
);

BuyerDeliveryDetailProjection _detail({String status = 'DISPATCHED'}) =>
    BuyerDeliveryDetailProjection(
      delivery: _delivery('SO-0001', status: status),
      events: [
        BuyerDeliveryEventProjection(
          type: 'HANDED_OVER',
          occurredAt: DateTime.utc(2026, 10, 9),
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
    userId: 'user-1',
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
  int validationCalls = 0;
  int acceptanceCalls = 0;
  int receiptCalls = 0;
  int evidenceUploadCalls = 0;
  int locationCalls = 0;
  String? lastValidatedToken;
  final receiptCommands = <Map<String, Object?>>[];
  final acceptanceCommands = <Map<String, Object?>>[];
  final evidenceUploadCommands = <Map<String, Object?>>[];
  Future<BuyerHandoffValidationProjection>? validationResult;
  Future<BuyerReceiptAcceptanceProjection>? acceptanceResult;
  Future<BuyerReceiptProjection>? receiptResult;
  Future<BuyerDeliveryTrackingProjection>? locationResult;

  @override
  Future<BuyerDeliveryPageProjection> list({required int page}) {
    pages.add(page);
    return listResult(page);
  }

  @override
  Future<BuyerDeliveryDetailProjection> detail(String deliveryId) {
    detailCalls++;
    return detailResult ?? Future.value(_detail());
  }

  @override
  Future<BuyerDeliveryTrackingProjection> liveLocation(String deliveryId) {
    locationCalls++;
    return locationResult ?? Future.value(_tracking());
  }

  @override
  Future<BuyerHandoffValidationProjection> validateHandoff(String token) {
    validationCalls++;
    lastValidatedToken = token;
    return validationResult ?? Future.value(_handoff());
  }

  @override
  Future<BuyerReceiptAcceptanceProjection> acceptHandoff({
    required String token,
    required String expectedDeliveryId,
    required String idempotencyKey,
  }) {
    acceptanceCalls++;
    acceptanceCommands.add({
      'token': token,
      'expectedDeliveryId': expectedDeliveryId,
      'idempotencyKey': idempotencyKey,
    });
    return acceptanceResult ?? Future.value(_acceptance());
  }

  @override
  Future<BuyerReceiptProjection> recordReceipt({
    required String deliveryId,
    required String token,
    required String decision,
    required num acceptedQuantity,
    required String? reason,
    required String idempotencyKey,
  }) {
    receiptCalls++;
    receiptCommands.add({
      'deliveryId': deliveryId,
      'token': token,
      'decision': decision,
      'acceptedQuantity': acceptedQuantity,
      'reason': reason,
      'idempotencyKey': idempotencyKey,
    });
    return receiptResult ??
        Future.value(
          _receipt(
            decision: decision,
            acceptedQuantity: acceptedQuantity,
            reason: reason,
          ),
        );
  }

  @override
  Future<List<BuyerReceiptEvidenceProjection>> listReceiptEvidence(
    String receiptId,
  ) async => const [];

  @override
  Future<BuyerReceiptEvidenceProjection> uploadReceiptEvidence({
    required String receiptId,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
  }) async {
    evidenceUploadCalls++;
    evidenceUploadCommands.add({
      'receiptId': receiptId,
      'filename': filename,
      'contentType': contentType,
      'bytes': List<int>.of(bytes),
      'idempotencyKey': idempotencyKey,
    });
    return BuyerReceiptEvidenceProjection(
      id: '00000000-0000-4000-8000-000000000005',
      receiptId: receiptId,
      lifecycleStatus: 'QUARANTINED',
      originalFilename: filename,
      contentType: contentType,
      byteSize: bytes.length,
      createdAt: DateTime.utc(2026, 10, 9, 12, 30),
      requestedByMembershipId: '00000000-0000-4000-8000-000000000020',
    );
  }
}

BuyerHandoffValidationProjection _handoff({
  String deliveryId = _deliveryId,
  String attemptId = '00000000-0000-4000-8000-000000000002',
}) => BuyerHandoffValidationProjection(
  handoffId: '00000000-0000-4000-8000-000000000003',
  deliveryId: deliveryId,
  attemptId: attemptId,
  expiresAt: DateTime.utc(2026, 10, 10),
  deliveryStatus: 'IN_TRANSIT',
  deliveredQuantity: 3,
);

BuyerReceiptAcceptanceProjection _acceptance({
  bool orderCompleted = false,
  bool replayed = false,
}) => BuyerReceiptAcceptanceProjection(
  receiptId: '00000000-0000-4000-8000-000000000012',
  deliveryId: _deliveryId,
  attemptId: '00000000-0000-4000-8000-000000000002',
  podId: '00000000-0000-4000-8000-000000000014',
  acceptedQuantity: 3,
  occurredAt: DateTime.utc(2026, 10, 9, 12),
  deliveryStatus: 'DELIVERED',
  podStatus: 'SEALED',
  salesOrderStatus: orderCompleted ? 'COMPLETED' : 'OPEN',
  deliveryVersion: 4,
  orderCompleted: orderCompleted,
  replayed: replayed,
);

BuyerReceiptProjection _receipt({
  String deliveryId = _deliveryId,
  String attemptId = '00000000-0000-4000-8000-000000000002',
  String decision = 'ACCEPTED',
  num acceptedQuantity = 3,
  String? reason,
  bool replayed = false,
}) => BuyerReceiptProjection(
  id: '00000000-0000-4000-8000-000000000004',
  deliveryId: deliveryId,
  attemptId: attemptId,
  decision: decision,
  driverDeliveredQuantity: 3,
  acceptedQuantity: acceptedQuantity,
  reason: reason,
  occurredAt: DateTime.utc(2026, 10, 9, 12),
  replayed: replayed,
);

BuyerDeliveryTrackingProjection _tracking({bool expired = false}) {
  final now = DateTime.now().toUtc();
  return BuyerDeliveryTrackingProjection(
    deliveryId: _deliveryId,
    sampleId: '00000000-0000-4000-8000-000000000006',
    latitude: -12.0464,
    longitude: -77.0428,
    accuracyMeters: 15,
    capturedAt: now.subtract(const Duration(seconds: 10)),
    expiresAt: expired
        ? now.subtract(const Duration(seconds: 1))
        : now.add(const Duration(minutes: 2)),
  );
}

final class _ReceiptStore implements BuyerReceiptIdempotencyStore {
  final entries = <String, ({String fingerprint, String key})>{};
  int _nextKey = 5;

  @override
  Future<String> keyFor({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  }) async {
    final identity = '$scopeKey|$deliveryId|$attemptId';
    final existing = entries[identity];
    if (existing != null) {
      if (existing.fingerprint != payloadFingerprint) {
        throw const BuyerReceiptCommandConflict();
      }
      return existing.key;
    }
    final key =
        '00000000-0000-4000-8000-${(_nextKey++).toString().padLeft(12, '0')}';
    entries[identity] = (fingerprint: payloadFingerprint, key: key);
    return key;
  }

  @override
  Future<void> release({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  }) async {
    final identity = '$scopeKey|$deliveryId|$attemptId';
    final existing = entries[identity];
    if (existing == null) return;
    if (existing.fingerprint != payloadFingerprint) {
      throw const BuyerReceiptCommandConflict();
    }
    entries.remove(identity);
  }
}

final class _EvidenceStore implements BuyerReceiptEvidenceIdempotencyStore {
  final keys = <String, String>{};
  int _nextKey = 20;

  @override
  Future<String> keyFor({
    required String scopeKey,
    required String receiptId,
    required String payloadFingerprint,
  }) async {
    final identity = '$scopeKey|$receiptId|$payloadFingerprint';
    return keys.putIfAbsent(
      identity,
      () =>
          '00000000-0000-4000-8000-${(_nextKey++).toString().padLeft(12, '0')}',
    );
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
