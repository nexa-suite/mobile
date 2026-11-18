import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_mobile_terms_repository.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/presentation/buyer_mobile_terms_view_model.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  final docV1 = _document('1');
  final docV2 = _document('2');

  test('keeps server-confirmed acceptance only through retryable same-session outage', () async {
    final repository = _Repository(docV1)
      ..status = BuyerMobileTermsStatus(
        document: docV1,
        accepted: true,
        acceptedAt: DateTime.utc(2026, 11, 17),
      );
    final access = _Access(_snapshot());
    final viewModel = BuyerMobileTermsViewModel(repository, access);
    await _flush();
    expect(viewModel.isAcceptedForCurrentSession, isTrue);

    repository.statusError = const NexaApiFailure(
      code: 'network_unavailable',
      userMessage: 'offline',
      retryable: true,
    );
    await viewModel.refreshAfterResume();

    expect(viewModel.isAcceptedForCurrentSession, isTrue);
    expect(viewModel.document?.version, '1');
    expect(viewModel.message, contains('Se conserva la aceptación'));
    viewModel.dispose();
    await access.dispose();
  });

  test(
    'does not retain acceptance after a new session or new terms version',
    () async {
      final repository = _Repository(docV1)
        ..status = BuyerMobileTermsStatus(
          document: docV1,
          accepted: true,
          acceptedAt: DateTime.utc(2026, 11, 17),
        );
      final access = _Access(_snapshot());
      final viewModel = BuyerMobileTermsViewModel(repository, access);
      await _flush();

      repository.statusError = const NexaApiFailure(
        code: 'network_unavailable',
        userMessage: 'offline',
        retryable: true,
      );
      access.emit(_snapshot(epoch: 2));
      await _flush();
      expect(viewModel.status, BuyerMobileTermsViewState.unavailable);
      expect(viewModel.isAcceptedForCurrentSession, isFalse);

      repository.statusError = null;
      repository.status = BuyerMobileTermsStatus(
        document: docV2,
        accepted: false,
        acceptedAt: null,
      );
      await viewModel.refresh();
      expect(viewModel.status, BuyerMobileTermsViewState.requiresAcceptance);
      expect(viewModel.consentRevision, contains('|2'));
      viewModel.dispose();
      await access.dispose();
    },
  );

  test(
    'checks server status after an unknown POST and waits for user retry',
    () async {
      final repository = _Repository(docV1)
        ..status = BuyerMobileTermsStatus(
          document: docV1,
          accepted: false,
          acceptedAt: null,
        )
        ..acceptError = const NexaApiFailure(
          code: 'request_outcome_unknown',
          userMessage: 'unknown',
          retryable: true,
        );
      final access = _Access(_snapshot());
      final viewModel = BuyerMobileTermsViewModel(repository, access);
      await _flush();
      expect(viewModel.canAccept, isTrue);

      await viewModel.acceptCurrentTerms();

      expect(repository.acceptCalls, 1);
      expect(repository.statusCalls, 2); // initial GET, then reconciliation GET
      expect(viewModel.status, BuyerMobileTermsViewState.requiresAcceptance);

      repository.acceptError = null;
      await viewModel.acceptCurrentTerms();
      expect(repository.acceptCalls, 2);
      expect(viewModel.isAcceptedForCurrentSession, isTrue);
      viewModel.dispose();
      await access.dispose();
    },
  );
}

BuyerMobileTermsDocument _document(String version) => BuyerMobileTermsDocument(
  documentId: 'mobile-terms',
  version: version,
  content: 'Approved terms version $version',
  contentDigest: 'digest-$version',
);

BuyerAccessSnapshot _snapshot({int epoch = 1}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: const BuyerAccessContext(
    membershipId: 'membership-1',
    tenantId: 'tenant-1',
    tenantName: 'Tenant',
    tenantSlug: 'tenant',
    workspaceId: 'workspace-1',
    workspaceName: 'Workspace',
    workspaceSlug: 'workspace',
    userId: 'user-1',
    roles: {'BUYER'},
  ),
);

Future<void> _flush() async {
  await Future<void>.delayed(Duration.zero);
  await Future<void>.delayed(Duration.zero);
}

final class _Repository implements BuyerMobileTermsRepository {
  _Repository(this.document);

  BuyerMobileTermsDocument document;
  BuyerMobileTermsStatus? status;
  Object? statusError;
  Object? acceptError;
  int statusCalls = 0;
  int acceptCalls = 0;

  @override
  Future<BuyerMobileTermsDocument> fetchPublicTerms() async => document;

  @override
  Future<BuyerMobileTermsStatus> fetchCurrentStatus() async {
    statusCalls++;
    final error = statusError;
    if (error != null) throw error;
    return status ??
        BuyerMobileTermsStatus(
          document: document,
          accepted: false,
          acceptedAt: null,
        );
  }

  @override
  Future<BuyerMobileTermsAcceptance> accept(
    BuyerMobileTermsDocument document,
  ) async {
    acceptCalls++;
    final error = acceptError;
    if (error != null) throw error;
    return BuyerMobileTermsAcceptance(
      documentId: document.documentId,
      version: document.version,
      contentDigest: document.contentDigest,
      accepted: true,
      acceptedAt: DateTime.utc(2026, 11, 17),
    );
  }
}

final class _Access implements BuyerAccessRepository {
  _Access(this._snapshot);

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
