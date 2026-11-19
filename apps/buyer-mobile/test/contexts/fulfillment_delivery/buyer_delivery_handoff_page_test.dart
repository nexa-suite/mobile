import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_deliveries_repository.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_receipt_draft_store.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_receipt_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/presentation/buyer_deliveries_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/presentation/buyer_delivery_detail_page.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_change_feed_repository.dart';
import 'package:nexa_buyer_mobile/core/application/error/nexa_api_failure.dart';
import 'package:provider/provider.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000004';
const _attemptId = '00000000-0000-4000-8000-000000000005';
const _authorityChangedMessage =
    'El acceso o el estado de la entrega cambió mientras escaneabas. '
    'No se envió este código; actualiza la entrega antes de volver a escanear.';

void main() {
  testWidgets('shows receipt action and proof status before delivery history', (
    tester,
  ) async {
    final access = _Access();
    final detail = _detail(_DeliveriesRepository(), access);
    await detail.load();
    final router = _router(detail, () async => null);

    await tester.pumpWidget(_app(access, router));
    await tester.pumpAndSettle();

    expect(find.text('Comprobante de entrega'), findsOneWidget);
    final scan = find.byKey(const Key('buyer-scan-delivery-qr'));
    expect(scan, findsOneWidget);
    expect(
      tester.getTopLeft(scan).dy,
      greaterThan(tester.getTopLeft(find.text('Comprobante de entrega')).dy),
    );
    expect(find.text('Historial de entrega'), findsNothing);
    await tester.scrollUntilVisible(find.text('Historial de entrega'), 300);
    expect(find.text('Historial de entrega'), findsOneWidget);

    await _dispose(tester, router, detail, access);
  });

  testWidgets(
    'does not submit a scanned token after authority changes and explains why',
    (tester) async {
      final access = _Access();
      final repository = _DeliveriesRepository();
      final detail = _detail(repository, access);
      await detail.load();
      final scanResult = Completer<String?>();
      final router = _router(detail, () => scanResult.future);

      await tester.pumpWidget(_app(access, router));
      await tester.pumpAndSettle();
      await tester.ensureVisible(
        find.byKey(const Key('buyer-scan-delivery-qr')),
      );
      await tester.tap(find.byKey(const Key('buyer-scan-delivery-qr')));
      await tester.pump();

      access.changeAuthority();
      await tester.pump();
      scanResult.complete('fresh-one-time-token');
      await tester.pumpAndSettle();

      expect(repository.acceptHandoffCalls, 0);
      expect(find.text(_authorityChangedMessage), findsOneWidget);

      await _dispose(tester, router, detail, access);
    },
  );

  testWidgets(
    'does not submit a scanned token after a permission refresh fails',
    (tester) async {
      final access = _Access();
      final repository = _DeliveriesRepository();
      final detail = _detail(repository, access);
      await detail.load();
      final scanResult = Completer<String?>();
      final router = _router(detail, () => scanResult.future);

      await tester.pumpWidget(_app(access, router));
      await tester.pumpAndSettle();
      await tester.ensureVisible(
        find.byKey(const Key('buyer-scan-delivery-qr')),
      );
      await tester.tap(find.byKey(const Key('buyer-scan-delivery-qr')));
      await tester.pump();

      final revision = detail.authorityRevision;
      repository.denyDetailRefresh = true;
      await detail.refresh();
      expect(detail.authorityRevision, revision);
      expect(detail.canAcceptHandoff, isFalse);

      scanResult.complete('fresh-one-time-token');
      await tester.pumpAndSettle();

      expect(repository.acceptHandoffCalls, 0);
      expect(find.text(_authorityChangedMessage), findsOneWidget);

      await _dispose(tester, router, detail, access);
    },
  );

  testWidgets('submits a scanned token while authority remains stable', (
    tester,
  ) async {
    final access = _Access();
    final repository = _DeliveriesRepository();
    final detail = _detail(repository, access);
    await detail.load();
    final scanResult = Completer<String?>();
    final router = _router(detail, () => scanResult.future);

    await tester.pumpWidget(_app(access, router));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('buyer-scan-delivery-qr')));
    await tester.tap(find.byKey(const Key('buyer-scan-delivery-qr')));
    await tester.pump();

    scanResult.complete('fresh-one-time-token');
    await tester.pumpAndSettle();

    expect(repository.acceptHandoffCalls, 1);
    expect(repository.lastAcceptedToken, 'fresh-one-time-token');
    expect(detail.receiptStatus, BuyerReceiptStatus.accepted);

    await _dispose(tester, router, detail, access);
  });

  testWidgets('shows QR failure and retries the same command', (tester) async {
    final access = _Access();
    final repository = _DeliveriesRepository()..failAcceptanceOnce = true;
    final detail = _detail(repository, access);
    await detail.load();
    final router = _router(detail, () async => 'fresh-one-time-token');

    await tester.pumpWidget(_app(access, router));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('buyer-scan-delivery-qr')));
    await tester.pumpAndSettle();

    expect(
      find.text('No se confirmó la recepción. Reintenta el mismo QR.'),
      findsOneWidget,
    );
    expect(find.byKey(const Key('buyer-scan-delivery-qr')), findsNothing);
    await tester.tap(find.byKey(const Key('buyer-retry-handoff-acceptance')));
    await tester.pumpAndSettle();

    expect(repository.acceptanceTokens, [
      'fresh-one-time-token',
      'fresh-one-time-token',
    ]);
    expect(repository.acceptanceKeys, hasLength(2));
    expect(repository.acceptanceKeys.first, repository.acceptanceKeys.last);
    expect(
      find.text('Recepción confirmada. El pedido está completo.'),
      findsOneWidget,
    );
    expect(find.text('Cantidad recibida'), findsOneWidget);
    expect(find.text('Revisar una diferencia'), findsNothing);
    await _dispose(tester, router, detail, access);
  });

  testWidgets('shows a definitive QR failure without a retry', (tester) async {
    final access = _Access();
    final repository = _DeliveriesRepository()..rejectAcceptance = true;
    final detail = _detail(repository, access);
    await detail.load();
    final router = _router(detail, () async => 'invalid-token');

    await tester.pumpWidget(_app(access, router));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('buyer-scan-delivery-qr')));
    await tester.pumpAndSettle();

    expect(
      find.text('No se registró la recepción ni el comprobante de entrega.'),
      findsOneWidget,
    );
    expect(
      find.byKey(const Key('buyer-retry-handoff-acceptance')),
      findsNothing,
    );
    expect(find.byKey(const Key('buyer-scan-delivery-qr')), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Hay una diferencia con mi entrega'),
      250,
    );
    await tester.tap(find.text('Hay una diferencia con mi entrega'));
    await tester.pumpAndSettle();
    expect(find.text('Revisar una diferencia'), findsOneWidget);
    await _dispose(tester, router, detail, access);
  });
}

Widget _app(_Access access, GoRouter router) => MultiProvider(
  providers: [
    Provider<BuyerAccessRepository>.value(value: access),
    Provider<BuyerReceiptDraftStore>.value(value: _DraftStore()),
    Provider<BuyerChangeFeedRepository>.value(value: _ChangeFeed()),
  ],
  child: MaterialApp.router(routerConfig: router),
);

GoRouter _router(
  BuyerDeliveryDetailViewModel detail,
  Future<String?> Function() scan,
) => GoRouter(
  initialLocation: '/deliveries/$_deliveryId',
  routes: [
    GoRoute(
      path: '/deliveries/:deliveryId',
      builder: (_, _) => ChangeNotifierProvider.value(
        value: detail,
        child: BuyerDeliveryDetailPage(scanDeliveryToken: scan),
      ),
    ),
  ],
);

BuyerDeliveryDetailViewModel _detail(
  _DeliveriesRepository repository,
  _Access access,
) => BuyerDeliveryDetailViewModel(
  repository,
  access,
  _deliveryId,
  receiptStore: _ReceiptIdempotencyStore(),
);

Future<void> _dispose(
  WidgetTester tester,
  GoRouter router,
  BuyerDeliveryDetailViewModel detail,
  _Access access,
) async {
  await tester.pumpWidget(const SizedBox.shrink());
  router.dispose();
  detail.dispose();
  await access.dispose();
}

final class _Access implements BuyerAccessRepository {
  final StreamController<BuyerAccessSnapshot> _changes =
      StreamController<BuyerAccessSnapshot>.broadcast(sync: true);
  int _authorityEpoch = 1;

  @override
  BuyerAccessSnapshot get snapshot => BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedIn,
    authorityEpoch: _authorityEpoch,
    currentContext: const BuyerAccessContext(
      userId: 'buyer-user',
      membershipId: 'membership-north',
      tenantId: 'tenant-north',
      tenantName: 'Empresa Norte',
      tenantSlug: 'empresa-norte',
      workspaceId: 'workspace-north',
      workspaceName: 'Sucursal principal',
      workspaceSlug: 'principal',
      roles: {'BUYER'},
      permissions: {'buyer.order.read', 'buyer.tracking.read'},
    ),
  );

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  void changeAuthority() {
    _authorityEpoch++;
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
  void invalidateLocalSession() {}

  @override
  Future<void> dispose() => _changes.close();
}

final class _ChangeFeed implements BuyerChangeFeedRepository {
  @override
  Stream<BuyerChangeFeedEvent> watch({required String readPermission}) =>
      const Stream.empty();
}

final class _DraftStore implements BuyerReceiptDraftStore {
  @override
  Future<BuyerReceiptDraft?> read({
    required String scopeKey,
    required String deliveryId,
  }) async => null;

  @override
  Future<void> write({
    required String scopeKey,
    required BuyerReceiptDraft draft,
  }) async {}

  @override
  Future<void> delete({
    required String scopeKey,
    required String deliveryId,
  }) async {}
}

final class _ReceiptIdempotencyStore implements BuyerReceiptIdempotencyStore {
  @override
  Future<String> keyFor({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  }) async => '00000000-0000-4000-8000-000000000006';

  @override
  Future<void> release({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  }) async {}
}

final class _DeliveriesRepository implements BuyerDeliveriesRepository {
  int acceptHandoffCalls = 0;
  String? lastAcceptedToken;
  bool denyDetailRefresh = false;
  bool failAcceptanceOnce = false;
  bool rejectAcceptance = false;
  final acceptanceTokens = <String>[];
  final acceptanceKeys = <String>[];

  @override
  Future<BuyerDeliveryDetailProjection> detail(String deliveryId) async {
    if (denyDetailRefresh) {
      throw const NexaApiFailure(
        code: 'forbidden',
        statusCode: 403,
        userMessage: 'No tienes permiso para consultar esta entrega.',
      );
    }
    return BuyerDeliveryDetailProjection(
      delivery: BuyerDeliveryProjection(
        id: _deliveryId,
        salesOrderNumber: 'SO-0001',
        status: 'DELIVERED',
        version: 3,
        createdAt: DateTime.utc(2026, 10, 9),
        updatedAt: DateTime.utc(2026, 10, 9),
        proofOfDeliveryStatus: 'DRAFT',
      ),
      events: const [],
    );
  }

  @override
  Future<BuyerReceiptAcceptanceProjection> acceptHandoff({
    required String token,
    required String expectedDeliveryId,
    required String idempotencyKey,
  }) async {
    acceptHandoffCalls++;
    lastAcceptedToken = token;
    acceptanceTokens.add(token);
    acceptanceKeys.add(idempotencyKey);
    if (failAcceptanceOnce) {
      failAcceptanceOnce = false;
      throw Exception('connection lost');
    }
    if (rejectAcceptance) {
      throw const NexaApiFailure(
        code: 'conflict',
        statusCode: 409,
        userMessage: 'Conflicto',
      );
    }
    return BuyerReceiptAcceptanceProjection(
      receiptId: 'receipt-1',
      deliveryId: expectedDeliveryId,
      attemptId: _attemptId,
      podId: 'pod-1',
      acceptedQuantity: 1,
      occurredAt: DateTime.utc(2026, 10, 9),
      deliveryStatus: 'DELIVERED',
      podStatus: 'SEALED',
      salesOrderStatus: 'COMPLETED',
      deliveryVersion: 4,
      orderCompleted: true,
      replayed: false,
    );
  }

  @override
  Future<BuyerDeliveryPageProjection> list({required int page}) =>
      throw UnimplementedError();

  @override
  Future<BuyerDeliveryTrackingProjection?> liveLocation(String deliveryId) =>
      throw UnimplementedError();

  @override
  Future<BuyerHandoffValidationProjection> validateHandoff(String token) =>
      throw UnimplementedError();

  @override
  Future<BuyerReceiptProjection> recordReceipt({
    required String deliveryId,
    required String token,
    required String decision,
    required num acceptedQuantity,
    required String? reason,
    required String idempotencyKey,
  }) => throw UnimplementedError();

  @override
  Future<List<BuyerReceiptEvidenceProjection>> listReceiptEvidence(
    String receiptId,
  ) => throw UnimplementedError();

  @override
  Future<BuyerReceiptEvidenceProjection> uploadReceiptEvidence({
    required String receiptId,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
  }) => throw UnimplementedError();
}
