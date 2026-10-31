import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/application/buyer_handoff_receipt_repository.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/presentation/buyer_handoff_receipt_card.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/presentation/buyer_handoff_receipt_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:provider/provider.dart';

void main() {
  testWidgets('manual confirmation stays usable with the keyboard open', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final access = _FakeAccess();
    final model = BuyerHandoffReceiptViewModel(
      _FakeReceiptRepository(),
      access,
      '00000000-0000-4000-8000-000000000001',
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              const SizedBox(height: 420),
              ChangeNotifierProvider.value(
                value: model,
                child: BuyerHandoffReceiptCard(
                  onConfirmed: () async {},
                  scanToken: () async => null,
                ),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.text('Escribir el código'));
    await tester.tap(find.text('Escribir el código'));
    await tester.pumpAndSettle();
    final input = find.byType(TextField);
    await tester.ensureVisible(input);
    await tester.tap(input);
    await tester.showKeyboard(input);
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    await tester.pumpAndSettle();

    final button = find.text('Confirmar que recibí el pedido');
    expect(find.text('Código del repartidor'), findsOneWidget);
    expect(tester.widget<TextField>(input).maxLength, 400);
    final labeledInput = find.bySemanticsLabel('Código del repartidor');
    expect(labeledInput, findsOneWidget);
    expect(
      tester.getSemantics(labeledInput).rect.size,
      tester.getRect(input).size,
    );
    expect(tester.getRect(button).bottom, lessThanOrEqualTo(544));

    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(
      find.text(
        'Este código no corresponde a una entrega. Usa el QR del repartidor.',
      ),
      findsOneWidget,
    );

    await tester.pumpWidget(const SizedBox.shrink());
    model.dispose();
    await access.dispose();
    semantics.dispose();
  });
}

final class _FakeReceiptRepository implements BuyerHandoffReceiptRepository {
  @override
  Future<BuyerHandoffReceipt> accept({
    required String token,
    required String expectedDeliveryId,
    required String idempotencyKey,
  }) => throw UnimplementedError();
}

final class _FakeAccess implements BuyerAccessRepository {
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast();
  @override
  final snapshot = const BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedIn,
    authorityEpoch: 1,
    currentContext: BuyerAccessContext(
      userId: 'user-1',
      membershipId: 'membership-1',
      tenantId: 'tenant-1',
      tenantName: 'Buyer',
      tenantSlug: 'buyer',
      workspaceId: 'workspace-1',
      workspaceName: 'Main',
      workspaceSlug: 'main',
      roles: {'BUYER'},
      permissions: {'buyer.order.read'},
      authorizationVersion: 1,
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
