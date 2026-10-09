import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/credit_receivables/application/credit_exposure_repository.dart';
import 'package:nexa_buyer_mobile/contexts/credit_receivables/presentation/buyer_credit_exposure_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

const _accountId = '00000000-0000-4000-8000-000000000001';
const _otherAccountId = '00000000-0000-4000-8000-000000000002';

void main() {
  test(
    'hides a receivable whose account differs from Buyer exposure',
    () async {
      final access = _FakeAccess(_snapshot());
      final repository = _FakeRepository(receivables: _page(_otherAccountId));
      final viewModel = BuyerCreditExposureViewModel(repository, access);

      await viewModel.refresh();

      expect(viewModel.exposure?.clientAccountId, _accountId);
      expect(viewModel.receivables, isEmpty);
      expect(viewModel.receivablesStatus, BuyerReceivablesStatus.unavailable);
      expect(viewModel.receivablesMessage, contains('no coincide'));
      expect(access.snapshot.isSignedIn, isTrue);

      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'authority change purges pending credit and receivables responses',
    () async {
      final access = _FakeAccess(_snapshot());
      final pendingReceivables = Completer<BuyerReceivablesPageProjection>();
      final repository = _FakeRepository(
        receivablesFuture: pendingReceivables.future,
      );
      final viewModel = BuyerCreditExposureViewModel(repository, access);

      final request = viewModel.refresh();
      await repository.receivablesStarted.future;
      expect(viewModel.exposure?.clientAccountId, _accountId);
      final accessCleared = Completer<void>();
      void observeClear() {
        if (viewModel.status == BuyerCreditExposureStatus.idle &&
            viewModel.exposure == null &&
            !accessCleared.isCompleted) {
          accessCleared.complete();
        }
      }

      viewModel.addListener(observeClear);
      access.emit(_snapshot(epoch: 2, tenantId: 'tenant-next'));
      await accessCleared.future;
      expect(viewModel.exposure, isNull);
      expect(viewModel.receivables, isEmpty);

      pendingReceivables.complete(_page(_accountId));
      await request;

      expect(viewModel.exposure, isNull);
      expect(viewModel.receivables, isEmpty);
      expect(viewModel.status, BuyerCreditExposureStatus.idle);

      viewModel.removeListener(observeClear);
      viewModel.dispose();
      await access.dispose();
    },
  );
}

BuyerCreditExposureProjection _exposure() =>
    const BuyerCreditExposureProjection(
      clientAccountId: _accountId,
      currency: 'PEN',
      creditLimit: '500.0',
      ledgerExposure: '75.0',
      outstandingReceivables: '75.0',
      reservedExposure: '0.0',
      used: '75.0',
      availableCredit: '425.0',
      active: true,
      asOf: null,
    );

BuyerReceivablesPageProjection _page(String accountId) =>
    BuyerReceivablesPageProjection(
      items: [
        BuyerReceivableProjection(
          id: '00000000-0000-4000-8000-000000000003',
          clientAccountId: accountId,
          number: 'REC-0001',
          currency: 'PEN',
          amount: '75.0',
          amountPaid: '0.0',
          remaining: '75.0',
          status: 'OPEN',
          version: 1,
        ),
      ],
      page: 0,
      size: 25,
      total: 1,
    );

BuyerAccessSnapshot _snapshot({
  int epoch = 1,
  String tenantId = 'tenant-current',
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
    roles: const {'BUYER'},
    permissions: permissions,
  ),
);

final class _FakeRepository implements BuyerCreditExposureRepository {
  _FakeRepository({this.receivables, this.receivablesFuture});

  final BuyerReceivablesPageProjection? receivables;
  final Future<BuyerReceivablesPageProjection>? receivablesFuture;
  final receivablesStarted = Completer<void>();

  @override
  Future<BuyerCreditExposureProjection> readCurrentBuyerExposure({
    required String currency,
  }) async => _exposure();

  @override
  Future<BuyerReceivablesPageProjection> listBuyerReceivables({
    required int page,
  }) {
    if (!receivablesStarted.isCompleted) receivablesStarted.complete();
    return receivablesFuture ?? Future.value(receivables ?? _page(_accountId));
  }
}

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast();

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  void emit(BuyerAccessSnapshot value) {
    _snapshot = value;
    _changes.add(value);
  }

  @override
  void invalidateLocalSession() =>
      emit(const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut));

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
