import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_payments_repository.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/payment_report_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/payments/presentation/buyer_payment_activity_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/payments/presentation/buyer_payment_activity_page.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _receivableId = '00000000-0000-4000-8000-000000000001';
const _accountId = '00000000-0000-4000-8000-000000000002';
const _paymentId = '00000000-0000-4000-8000-000000000003';
const _retryId = '00000000-0000-4000-8000-000000000004';

void main() {
  test(
    'does not issue a report without the exact payment.create grant',
    () async {
      final access = _FakeAccess(
        _snapshot(permissions: const {'payment.read'}),
      );
      final repository = _FakePaymentsRepository();
      final store = _FakeIdempotencyStore();
      final viewModel = _viewModel(access, repository, store);

      await viewModel.reportBankTransfer('BANK-REF-71');

      expect(
        viewModel.reportStatus,
        BuyerTransferReportStatus.permissionDenied,
      );
      expect(repository.reportCalls, isEmpty);
      expect(store.keyCalls, 0);
      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.invalidations, 0);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('403 report denial preserves the active Buyer session', () async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakePaymentsRepository()
      ..reportHandler = (_, _, _) => Future.error(
        const NexaApiFailure(
          code: 'permission_denied',
          statusCode: 403,
          userMessage: 'Not permitted.',
        ),
      );
    final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());

    await viewModel.reportBankTransfer('BANK-REF-71');

    expect(viewModel.reportStatus, BuyerTransferReportStatus.permissionDenied);
    expect(access.snapshot.isSignedIn, isTrue);
    expect(access.invalidations, 0);

    viewModel.dispose();
    await access.dispose();
  });

  test('ACCESS_CONTEXT_INVALID clears only the active authority', () async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakePaymentsRepository()
      ..reportHandler = (_, _, _) => Future.error(
        const NexaApiFailure(
          code: 'ACCESS_CONTEXT_INVALID',
          statusCode: 403,
          userMessage: 'The active access context is invalid.',
        ),
      );
    final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());

    await viewModel.reportBankTransfer('BANK-REF-71');

    expect(access.snapshot.isSignedIn, isFalse);
    expect(access.invalidations, 1);
    expect(viewModel.reportedPayment, isNull);
    expect(viewModel.history, isNull);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'current 401 expires the active session after a command response',
    () async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakePaymentsRepository()
        ..reportHandler = (_, _, _) => Future.error(
          const NexaApiFailure(
            code: 'session_expired',
            statusCode: 401,
            userMessage: 'Sign in again.',
          ),
        );
      final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());

      await viewModel.reportBankTransfer('BANK-REF-71');

      expect(access.snapshot.isSignedIn, isFalse);
      expect(access.invalidations, 1);
      expect(viewModel.reportedPayment, isNull);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('stale report completion cannot publish under a new context', () async {
    final access = _FakeAccess(_snapshot());
    final pending = Completer<BuyerPaymentProjection>();
    final reportStarted = Completer<void>();
    final repository = _FakePaymentsRepository();
    repository.reportHandler = (_, _, _) => pending.future;
    repository.reportStarted = reportStarted;
    final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());

    final request = viewModel.reportBankTransfer('BANK-REF-71');
    await reportStarted.future;
    access.emit(_snapshot(epoch: 2, tenantId: 'tenant-next'));
    pending.complete(_payment());
    await request;

    expect(viewModel.reportedPayment, isNull);
    expect(viewModel.reportStatus, BuyerTransferReportStatus.idle);
    expect(access.snapshot.isSignedIn, isTrue);
    expect(access.invalidations, 0);

    viewModel.dispose();
    await access.dispose();
  });

  test(
    'late 401 from an old report cannot expire the replacement session',
    () async {
      final access = _FakeAccess(_snapshot());
      final pending = Completer<BuyerPaymentProjection>();
      final reportStarted = Completer<void>();
      final repository = _FakePaymentsRepository();
      repository.reportHandler = (_, _, _) => pending.future;
      repository.reportStarted = reportStarted;
      final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());

      final request = viewModel.reportBankTransfer('BANK-REF-71');
      await reportStarted.future;
      access.emit(_snapshot(epoch: 2, tenantId: 'tenant-next'));
      pending.completeError(
        const NexaApiFailure(
          code: 'session_expired',
          statusCode: 401,
          userMessage: 'Sign in again.',
        ),
      );
      await request;

      expect(access.snapshot.isSignedIn, isTrue);
      expect(access.snapshot.authorityEpoch, 2);
      expect(access.invalidations, 0);
      expect(viewModel.reportedPayment, isNull);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test('ambiguous retry reuses the same durable command identity', () async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakePaymentsRepository();
    var attempt = 0;
    repository.reportHandler = (_, _, _) {
      attempt++;
      if (attempt == 1) return Future.error(StateError('connection closed'));
      return Future.value(_payment());
    };
    final store = _FakeIdempotencyStore();
    final viewModel = _viewModel(access, repository, store);

    await viewModel.reportBankTransfer(' BANK-REF-71 ');
    expect(viewModel.reportStatus, BuyerTransferReportStatus.uncertain);
    await viewModel.reportBankTransfer('BANK-REF-71');

    expect(repository.reportCalls, hasLength(2));
    expect(repository.reportCalls.map((call) => call.idempotencyKey).toSet(), {
      _retryId,
    });
    expect(repository.reportCalls.first.reference, 'BANK-REF-71');
    expect(viewModel.reportStatus, BuyerTransferReportStatus.reported);
    expect(viewModel.reportedPayment?.status, 'PROCESSING');
    expect(viewModel.reportMessage, contains('no confirma'));

    viewModel.dispose();
    await access.dispose();
  });

  test('payment history checks payment.read before calling the API', () async {
    final access = _FakeAccess(
      _snapshot(permissions: const {'payment.create'}),
    );
    final repository = _FakePaymentsRepository();
    final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());

    await viewModel.loadHistory(0);

    expect(viewModel.historyStatus, BuyerPaymentHistoryStatus.permissionDenied);
    expect(repository.historyCalls, 0);
    expect(access.snapshot.isSignedIn, isTrue);
    expect(access.invalidations, 0);

    viewModel.dispose();
    await access.dispose();
  });

  testWidgets('Buyer can report a reference and sees the server status', (
    tester,
  ) async {
    final access = _FakeAccess(_snapshot());
    final repository = _FakePaymentsRepository();
    final viewModel = _viewModel(access, repository, _FakeIdempotencyStore());
    addTearDown(viewModel.dispose);
    addTearDown(access.dispose);

    await tester.pumpWidget(
      ChangeNotifierProvider<BuyerPaymentActivityViewModel>.value(
        value: viewModel,
        child: const MaterialApp(home: BuyerPaymentActivityPage()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'BANK-REF-71');
    final reportAction = find.byKey(const Key('buyer-payment-report-action'));
    await tester.ensureVisible(reportAction);
    await tester.pumpAndSettle();
    await tester.tap(reportAction);
    await tester.pumpAndSettle();

    expect(repository.reportCalls, hasLength(1));
    expect(repository.reportCalls.single.reference, 'BANK-REF-71');
    expect(find.textContaining('Estado actual: PROCESSING'), findsOneWidget);
    final reportResult = tester.widget<Text>(
      find.byKey(const Key('payment-report-result')),
    );
    expect(reportResult.data, contains('no confirma que el pago'));
  });
}

BuyerPaymentActivityViewModel _viewModel(
  _FakeAccess access,
  _FakePaymentsRepository repository,
  _FakeIdempotencyStore store,
) => BuyerPaymentActivityViewModel(
  repository,
  access,
  store,
  _receivableId,
  expectedClientAccountId: _accountId,
);

BuyerPaymentProjection _payment() => BuyerPaymentProjection(
  id: _paymentId,
  receivableId: _receivableId,
  method: 'BANK_TRANSFER',
  status: 'PROCESSING',
  amount: '75.00',
  currency: 'PEN',
  createdAt: DateTime.utc(2026, 10, 9, 12),
);

BuyerAccessSnapshot _snapshot({
  int epoch = 1,
  String tenantId = 'tenant-current',
  Set<String> permissions = const {'payment.read', 'payment.create'},
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
    roles: const {'BUYER'},
    permissions: permissions,
  ),
);

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast();
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
  void invalidateLocalSession() {
    invalidations++;
    _snapshot = const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut);
    _changes.add(_snapshot);
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

final class _FakePaymentsRepository implements BuyerPaymentsRepository {
  final List<_ReportCall> reportCalls = [];
  int historyCalls = 0;
  Future<BuyerPaymentProjection> Function(
    String receivableId,
    String reference,
    String idempotencyKey,
  )?
  reportHandler;
  Completer<void>? reportStarted;

  @override
  Future<BuyerPaymentHistoryPageProjection> listForReceivable({
    required String receivableId,
    required int page,
    String? expectedClientAccountId,
  }) async {
    historyCalls++;
    return BuyerPaymentHistoryPageProjection(
      items: const [],
      page: page,
      size: 25,
      total: 0,
    );
  }

  @override
  Future<BuyerPaymentProjection> reportBankTransfer({
    required String receivableId,
    required String reference,
    required String idempotencyKey,
  }) {
    reportCalls.add(_ReportCall(reference, idempotencyKey));
    if (reportStarted != null && !reportStarted!.isCompleted) {
      reportStarted!.complete();
    }
    return reportHandler?.call(receivableId, reference, idempotencyKey) ??
        Future.value(_payment());
  }
}

final class _ReportCall {
  const _ReportCall(this.reference, this.idempotencyKey);

  final String reference;
  final String idempotencyKey;
}

final class _FakeIdempotencyStore implements PaymentReportIdempotencyStore {
  final Map<String, String> _keys = {};
  int keyCalls = 0;

  @override
  Future<String> keyFor({
    required String scopeKey,
    required String receivableId,
    required String referenceFingerprint,
  }) async {
    keyCalls++;
    return _keys.putIfAbsent(
      '$scopeKey|$receivableId|$referenceFingerprint',
      () => _retryId,
    );
  }
}
