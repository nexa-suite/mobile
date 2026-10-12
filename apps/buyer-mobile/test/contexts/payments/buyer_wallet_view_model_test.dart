import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_wallet_repository.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_wallet_recharge_command_store.dart';
import 'package:nexa_buyer_mobile/contexts/payments/presentation/buyer_wallet_page.dart';
import 'package:nexa_buyer_mobile/contexts/payments/presentation/buyer_wallet_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';
import 'package:provider/provider.dart';

void main() {
  test(
    'requires both the Buyer role and payment.read before calling API',
    () async {
      final access = _FakeAccess(_snapshot(roles: const {'LOGISTICS'}));
      final repository = _FakeWalletRepository();
      final viewModel = BuyerWalletViewModel(
        repository,
        access,
        _FakeRechargeStore(),
      );

      await viewModel.refresh();

      expect(viewModel.status, BuyerWalletViewStatus.permissionDenied);
      expect(repository.readCalls, isEmpty);
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);
      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'maps API 503 to explicit unavailable state without expiring session',
    () async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakeWalletRepository()
        ..readHandler = (_) => Future.error(
          const NexaApiFailure(
            code: 'WALLET_UNAVAILABLE',
            statusCode: 503,
            userMessage: 'Temporarily unavailable.',
          ),
        );
      final viewModel = BuyerWalletViewModel(
        repository,
        access,
        _FakeRechargeStore(),
      );

      await viewModel.refresh();

      expect(viewModel.status, BuyerWalletViewStatus.unavailable);
      expect(
        viewModel.message,
        'La billetera no está disponible en este momento.',
      );
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);
      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'late wallet response cannot publish after access context changes',
    () async {
      final access = _FakeAccess(_snapshot());
      final pending = Completer<BuyerWalletProjection>();
      final started = Completer<void>();
      final repository = _FakeWalletRepository()
        ..readHandler = (_) {
          started.complete();
          return pending.future;
        };
      final viewModel = BuyerWalletViewModel(
        repository,
        access,
        _FakeRechargeStore(),
      );

      final read = viewModel.refresh();
      await started.future;
      access.emit(_snapshot(epoch: 2, tenantId: 'tenant-next'));
      pending.complete(_activeWallet());
      await read;

      expect(viewModel.wallet, isNull);
      expect(viewModel.status, BuyerWalletViewStatus.idle);
      expect(access.snapshot.authorityEpoch, 2);
      expect(access.invalidations, 0);
      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'checks the live access lease before publishing a completed response',
    () async {
      final access = _FakeAccess(_snapshot());
      final pending = Completer<BuyerWalletProjection>();
      final started = Completer<void>();
      final repository = _FakeWalletRepository()
        ..readHandler = (_) {
          started.complete();
          return pending.future;
        };
      final viewModel = BuyerWalletViewModel(
        repository,
        access,
        _FakeRechargeStore(),
      );

      final read = viewModel.refresh();
      await started.future;
      final changedContext = _snapshot(epoch: 2, tenantId: 'tenant-next');
      access.changeSnapshotWithoutNotify(changedContext);
      pending.complete(_activeWallet());
      await read;

      expect(viewModel.wallet, isNull);
      expect(viewModel.status, BuyerWalletViewStatus.idle);

      access.emit(changedContext);
      expect(viewModel.status, BuyerWalletViewStatus.idle);
      expect(viewModel.wallet, isNull);
      expect(access.invalidations, 0);
      viewModel.dispose();
      await access.dispose();
    },
  );

  testWidgets('shows uninitialized wallet without fabricating zero balances', (
    tester,
  ) async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakeWalletRepository()
      ..readHandler = (_) async => _notInitializedWallet();
    final viewModel = BuyerWalletViewModel(
      repository,
      access,
      _FakeRechargeStore(),
    );
    addTearDown(viewModel.dispose);
    addTearDown(access.dispose);

    await tester.pumpWidget(
      ChangeNotifierProvider<BuyerWalletViewModel>.value(
        value: viewModel,
        child: const MaterialApp(home: BuyerWalletPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Billetera no inicializada'), findsOneWidget);
    expect(
      find.text('No hay saldos disponibles para mostrar.'),
      findsOneWidget,
    );
    expect(find.textContaining('0 PEN'), findsNothing);
    expect(find.textContaining('Disponible'), findsNothing);
  });

  testWidgets('shows active server balances and movements', (tester) async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakeWalletRepository()
      ..readHandler = (_) async => _activeWallet();
    final viewModel = BuyerWalletViewModel(
      repository,
      access,
      _FakeRechargeStore(),
    );
    addTearDown(viewModel.dispose);
    addTearDown(access.dispose);

    await tester.pumpWidget(
      ChangeNotifierProvider<BuyerWalletViewModel>.value(
        value: viewModel,
        child: const MaterialApp(home: BuyerWalletPage()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('90.50 PEN'), findsOneWidget);
    expect(find.text('120.50 PEN'), findsOneWidget);
    expect(find.text('30.00 PEN'), findsOneWidget);
    expect(find.text('PURCHASE RESERVED'), findsOneWidget);
    expect(find.text('-30.00 PEN'), findsOneWidget);
  });
}

BuyerWalletProjection _activeWallet() => BuyerWalletProjection(
  state: BuyerWalletState.active,
  currency: 'PEN',
  postedBalance: '120.50',
  reservedBalance: '30.00',
  availableBalance: '90.50',
  movements: BuyerWalletMovementsPageProjection(
    items: [
      BuyerWalletMovementProjection(
        type: 'PURCHASE_RESERVED',
        amountDelta: '-30.00',
        occurredAt: DateTime.utc(2026, 10, 9, 12),
      ),
    ],
    page: 0,
    size: 25,
    total: 1,
  ),
);

BuyerWalletProjection _notInitializedWallet() => BuyerWalletProjection(
  state: BuyerWalletState.notInitialized,
  currency: 'PEN',
  postedBalance: null,
  reservedBalance: null,
  availableBalance: null,
  movements: const BuyerWalletMovementsPageProjection(
    items: [],
    page: 0,
    size: 25,
    total: 0,
  ),
);

BuyerAccessSnapshot _snapshot({
  int epoch = 1,
  String tenantId = 'tenant-current',
  Set<String> roles = const {'BUYER'},
  Set<String> permissions = const {'payment.read'},
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    membershipId: 'membership-current',
    tenantId: tenantId,
    tenantName: 'Buyer',
    tenantSlug: 'buyer',
    workspaceId: 'workspace-current',
    workspaceName: 'Primary',
    workspaceSlug: 'primary',
    roles: roles,
    permissions: permissions,
  ),
);

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast(sync: true);
  int invalidations = 0;

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  @override
  void invalidateLocalSession() {
    invalidations++;
    _snapshot = const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut);
    _changes.add(_snapshot);
  }

  void emit(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
    _changes.add(snapshot);
  }

  void changeSnapshotWithoutNotify(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
  }

  @override
  Future<void> signIn({
    required String identifier,
    required String password,
  }) async {}

  @override
  Future<void> selectContext(String membershipId) async {}

  @override
  Future<void> signOut() async {}

  @override
  Future<void> dispose() => _changes.close();
}

final class _FakeWalletRepository implements BuyerWalletRepository {
  final List<int> readCalls = [];
  Future<BuyerWalletProjection> Function(int page)? readHandler;

  @override
  Future<BuyerWalletProjection> readCurrentWallet({required int page}) {
    readCalls.add(page);
    final handler = readHandler;
    if (handler != null) return handler(page);
    return Future.value(_activeWallet());
  }

  @override
  Future<BuyerWalletRechargeProjection> createRecharge({
    required String amount,
    required String idempotencyKey,
  }) => Future.error(UnimplementedError());

  @override
  Future<BuyerWalletRechargeProjection> readRecharge({
    required String rechargeId,
  }) => Future.error(UnimplementedError());
}

final class _FakeRechargeStore implements BuyerWalletRechargeCommandStore {
  BuyerWalletRechargeCommand? command;

  @override
  Future<BuyerWalletRechargeCommand?> load({required String scopeKey}) async =>
      command;

  @override
  Future<BuyerWalletRechargeCommand> prepare({
    required String scopeKey,
    required String amount,
  }) async {
    final existing = command;
    if (existing != null) return existing;
    final created = BuyerWalletRechargeCommand(
      amount: amount,
      idempotencyKey: 'recharge-idempotency-key',
    );
    command = created;
    return created;
  }

  @override
  Future<bool> recordCreated({
    required String scopeKey,
    required String idempotencyKey,
    required String rechargeId,
  }) async {
    if (command?.idempotencyKey != idempotencyKey) return false;
    command = command!.withRechargeId(rechargeId);
    return true;
  }

  @override
  Future<bool> clear({
    required String scopeKey,
    required String idempotencyKey,
  }) async {
    if (command?.idempotencyKey != idempotencyKey) return false;
    command = null;
    return true;
  }
}
