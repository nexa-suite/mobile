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
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';
import 'package:provider/provider.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000004';
const _attemptId = '00000000-0000-4000-8000-000000000005';
const _scopeKey = 'membership-north|tenant-north|workspace-north';

void main() {
  testWidgets(
    'restores and saves a local receipt draft while delivery status is unavailable',
    (tester) async {
      final access = _Access();
      final draftStore = _DraftStore()
        ..draft = const BuyerReceiptDraft(
          deliveryId: _deliveryId,
          attemptId: _attemptId,
          decision: 'DISPUTED',
          acceptedQuantity: '4',
          reason: 'Falta una caja',
        );
      final repository = _DeliveriesRepository();
      final detail = BuyerDeliveryDetailViewModel(
        repository,
        access,
        _deliveryId,
        receiptStore: _ReceiptIdempotencyStore(),
      );
      final loading = detail.load();
      final router = GoRouter(
        initialLocation: '/deliveries/$_deliveryId',
        routes: [
          GoRoute(
            path: '/deliveries/:deliveryId',
            builder: (_, _) => ChangeNotifierProvider.value(
              value: detail,
              child: const BuyerDeliveryDetailPage(),
            ),
          ),
        ],
      );

      await tester.pumpWidget(
        MultiProvider(
          providers: [
            Provider<BuyerAccessRepository>.value(value: access),
            Provider<BuyerReceiptDraftStore>.value(value: draftStore),
            Provider<BuyerChangeFeedRepository>.value(value: _ChangeFeed()),
          ],
          child: MaterialApp.router(routerConfig: router),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 10));

      expect(
        find.byKey(const Key('buyer-receipt-draft-panel')),
        findsOneWidget,
      );
      expect(
        find.text(
          'Borrador local · sin conexión; no enviado ni confirmado por Nexa.',
        ),
        findsOneWidget,
      );
      expect(detail.status, BuyerDeliveryDetailStatus.loading);

      await tester.ensureVisible(
        find.byKey(const Key('buyer-restore-receipt-draft')),
      );
      await tester.tap(find.byKey(const Key('buyer-restore-receipt-draft')));
      await tester.pump();
      expect(
        tester
            .widget<TextField>(
              find.byKey(const Key('buyer-offline-draft-quantity')),
            )
            .controller
            ?.text,
        '4',
      );
      expect(
        tester
            .widget<TextField>(
              find.byKey(const Key('buyer-offline-draft-reason')),
            )
            .controller
            ?.text,
        'Falta una caja',
      );

      await tester.ensureVisible(
        find.byKey(const Key('buyer-offline-draft-quantity')),
      );
      await tester.enterText(
        find.byKey(const Key('buyer-offline-draft-quantity')),
        '3.5',
      );
      await tester.enterText(
        find.byKey(const Key('buyer-offline-draft-reason')),
        'La caja llegó dañada',
      );
      await tester.ensureVisible(
        find.byKey(const Key('buyer-save-offline-receipt-draft')),
      );
      await tester.tap(
        find.byKey(const Key('buyer-save-offline-receipt-draft')),
      );
      await tester.pumpAndSettle();

      expect(draftStore.writes, 1);
      expect(draftStore.draft?.acceptedQuantity, '3.5');
      expect(draftStore.draft?.reason, 'La caja llegó dañada');
      expect(repository.recordReceiptCalls, 0);
      expect(find.byKey(const Key('buyer-submit-receipt')), findsNothing);

      repository.detailResult.completeError(
        const NexaApiFailure(
          code: 'service_unavailable',
          statusCode: 503,
          userMessage: 'El servicio de entregas no está disponible ahora.',
        ),
      );
      await loading;
      await tester.pumpAndSettle();

      expect(detail.status, BuyerDeliveryDetailStatus.unavailable);
      expect(
        find.byKey(const Key('buyer-receipt-draft-panel')),
        findsOneWidget,
      );
      expect(
        find.text('El servicio de entregas no está disponible ahora.'),
        findsOneWidget,
      );
      expect(repository.recordReceiptCalls, 0);
      expect(find.byKey(const Key('buyer-submit-receipt')), findsNothing);

      await tester.pumpWidget(const SizedBox.shrink());
      router.dispose();
      detail.dispose();
      await access.dispose();
    },
  );
}

final class _Access implements BuyerAccessRepository {
  final StreamController<BuyerAccessSnapshot> _changes =
      StreamController<BuyerAccessSnapshot>.broadcast(sync: true);

  @override
  BuyerAccessSnapshot get snapshot => const BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedIn,
    authorityEpoch: 1,
    currentContext: BuyerAccessContext(
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
  BuyerReceiptDraft? draft;
  int writes = 0;

  @override
  Future<BuyerReceiptDraft?> read({
    required String scopeKey,
    required String deliveryId,
  }) async =>
      scopeKey == _scopeKey && draft?.deliveryId == deliveryId ? draft : null;

  @override
  Future<void> write({
    required String scopeKey,
    required BuyerReceiptDraft draft,
  }) async {
    if (scopeKey != _scopeKey || draft.deliveryId != _deliveryId) {
      throw StateError('The draft must remain in the active scope.');
    }
    writes++;
    this.draft = draft;
  }

  @override
  Future<void> delete({
    required String scopeKey,
    required String deliveryId,
  }) async {
    if (scopeKey == _scopeKey && deliveryId == _deliveryId) draft = null;
  }
}

final class _DeliveriesRepository implements BuyerDeliveriesRepository {
  final Completer<BuyerDeliveryDetailProjection> detailResult = Completer();
  int recordReceiptCalls = 0;

  @override
  Future<BuyerDeliveryPageProjection> list({required int page}) =>
      throw UnimplementedError();

  @override
  Future<BuyerDeliveryDetailProjection> detail(String deliveryId) =>
      detailResult.future;

  @override
  Future<BuyerDeliveryTrackingProjection?> liveLocation(String deliveryId) =>
      throw UnimplementedError();

  @override
  Future<BuyerHandoffValidationProjection> validateHandoff(String token) =>
      throw UnimplementedError();

  @override
  Future<BuyerReceiptAcceptanceProjection> acceptHandoff({
    required String token,
    required String expectedDeliveryId,
    required String idempotencyKey,
  }) => throw UnimplementedError();

  @override
  Future<BuyerReceiptProjection> recordReceipt({
    required String deliveryId,
    required String token,
    required String decision,
    required num acceptedQuantity,
    required String? reason,
    required String idempotencyKey,
  }) {
    recordReceiptCalls++;
    throw StateError('Offline receipt submission must remain unavailable.');
  }

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
