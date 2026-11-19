import 'dart:async';

import 'package:flutter/foundation.dart';

import '../application/buyer_access_repository.dart';
import '../application/buyer_mobile_terms_repository.dart';

enum BuyerMobileTermsViewState {
  idle,
  loading,
  publicOnly,
  requiresAcceptance,
  accepted,
  submitting,
  unavailable,
}

enum BuyerMobileTermsNotice {
  refreshKeptAcceptance,
  acceptanceUnconfirmed,
  acceptanceAccessDenied,
  documentChanged,
  checkingAcceptance,
  newVersion,
  acceptanceNotRecorded,
  cannotConfirmResult,
  statusAccessDenied,
  termsUnavailable,
}

final class BuyerMobileTermsViewModel extends ChangeNotifier {
  BuyerMobileTermsViewModel(this._repository, this._access)
    : _sessionKey = _keyFor(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
    if (_sessionKey != null) unawaited(refresh());
  }

  final BuyerMobileTermsRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _sessionKey;
  int _requestGeneration = 0;

  BuyerMobileTermsViewState status = BuyerMobileTermsViewState.idle;
  BuyerMobileTermsDocument? document;
  DateTime? acceptedAt;
  BuyerMobileTermsNotice? notice;

  bool get hasActiveBuyerSession => _keyFor(_access.snapshot) != null;

  bool get isAcceptedForCurrentSession =>
      status == BuyerMobileTermsViewState.accepted &&
      _sessionKey != null &&
      _sessionKey == _keyFor(_access.snapshot);

  bool get canAccept =>
      status == BuyerMobileTermsViewState.requiresAcceptance &&
      document != null &&
      _sessionKey != null &&
      _sessionKey == _keyFor(_access.snapshot);

  bool get isLoading =>
      status == BuyerMobileTermsViewState.loading ||
      status == BuyerMobileTermsViewState.submitting;

  /// Used only to reset the ephemeral checkbox after a session or version change.
  String? get consentRevision {
    final identity = _keyFor(_access.snapshot);
    final version = document?.version;
    if (identity == null || version == null) return null;
    return '$identity|$version';
  }

  Future<void> refresh() async {
    if (status == BuyerMobileTermsViewState.loading ||
        status == BuyerMobileTermsViewState.submitting) {
      return;
    }
    final identity = _keyFor(_access.snapshot);
    final generation = ++_requestGeneration;
    final previouslyAccepted =
        status == BuyerMobileTermsViewState.accepted &&
        _sessionKey == identity &&
        identity != null;
    final previousDocument = document;
    final previousAcceptedAt = acceptedAt;
    _sessionKey = identity;
    if (!previouslyAccepted) status = BuyerMobileTermsViewState.loading;
    if (!previouslyAccepted) acceptedAt = null;
    notice = null;
    notifyListeners();
    try {
      if (identity == null) {
        final currentTerms = await _repository.fetchPublicTerms();
        if (!_isCurrent(generation, null)) return;
        document = currentTerms;
        status = BuyerMobileTermsViewState.publicOnly;
      } else {
        final currentStatus = await _repository.fetchCurrentStatus();
        if (!_isCurrent(generation, identity)) return;
        _applyCurrentStatus(currentStatus);
      }
    } catch (error) {
      if (!_isCurrent(generation, identity)) return;
      if (error == BuyerMobileTermsFailure.sessionRequired) {
        _access.invalidateLocalSession();
        return;
      }
      if (previouslyAccepted &&
          error == BuyerMobileTermsFailure.connectionUnavailable) {
        document = previousDocument;
        status = BuyerMobileTermsViewState.accepted;
        acceptedAt = previousAcceptedAt;
        notice = BuyerMobileTermsNotice.refreshKeptAcceptance;
      } else {
        _setUnavailable(_messageFor(error));
      }
    }
    notifyListeners();
  }

  /// Rechecks the server when the app returns to the foreground.
  Future<void> refreshAfterResume() => refresh();

  Future<void> acceptCurrentTerms() async {
    final currentDocument = document;
    final identity = _sessionKey;
    if (!canAccept || currentDocument == null || identity == null) return;
    final generation = ++_requestGeneration;
    status = BuyerMobileTermsViewState.submitting;
    acceptedAt = null;
    notice = null;
    notifyListeners();
    try {
      final result = await _repository.accept(currentDocument);
      if (!_isCurrent(generation, identity)) return;
      if (_acceptanceMatches(result, currentDocument) && result.accepted) {
        status = BuyerMobileTermsViewState.accepted;
        acceptedAt = result.acceptedAt;
      } else {
        await _refreshAfterPost(
          generation,
          identity,
          BuyerMobileTermsNotice.acceptanceUnconfirmed,
        );
        return;
      }
    } on BuyerMobileTermsFailure catch (failure) {
      if (!_isCurrent(generation, identity)) return;
      if (failure == BuyerMobileTermsFailure.sessionRequired) {
        _access.invalidateLocalSession();
        return;
      }
      if (failure == BuyerMobileTermsFailure.accessDenied) {
        _setUnavailable(BuyerMobileTermsNotice.acceptanceAccessDenied);
      } else {
        await _refreshAfterPost(
          generation,
          identity,
          failure == BuyerMobileTermsFailure.documentChanged
              ? BuyerMobileTermsNotice.documentChanged
              : BuyerMobileTermsNotice.checkingAcceptance,
        );
        return;
      }
    } catch (_) {
      if (!_isCurrent(generation, identity)) return;
      await _refreshAfterPost(
        generation,
        identity,
        BuyerMobileTermsNotice.checkingAcceptance,
      );
      return;
    }
    notifyListeners();
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextKey = _keyFor(snapshot);
    if (nextKey == _sessionKey) return;
    _sessionKey = nextKey;
    _requestGeneration++;
    document = null;
    acceptedAt = null;
    notice = null;
    status = BuyerMobileTermsViewState.idle;
    notifyListeners();
    unawaited(refresh());
  }

  Future<void> _refreshAfterPost(
    int generation,
    String identity,
    BuyerMobileTermsNotice outcomeMessage,
  ) async {
    if (!_isCurrent(generation, identity)) return;
    status = BuyerMobileTermsViewState.loading;
    notice = outcomeMessage;
    notifyListeners();
    try {
      final currentStatus = await _repository.fetchCurrentStatus();
      if (!_isCurrent(generation, identity)) return;
      final previousVersion = document?.version;
      _applyCurrentStatus(currentStatus);
      if (status != BuyerMobileTermsViewState.accepted) {
        notice = currentStatus.document.version != previousVersion
            ? BuyerMobileTermsNotice.newVersion
            : BuyerMobileTermsNotice.acceptanceNotRecorded;
      }
    } catch (error) {
      if (!_isCurrent(generation, identity)) return;
      if (error == BuyerMobileTermsFailure.sessionRequired) {
        _access.invalidateLocalSession();
        return;
      }
      _setUnavailable(BuyerMobileTermsNotice.cannotConfirmResult);
    }
    notifyListeners();
  }

  void _applyCurrentStatus(BuyerMobileTermsStatus currentStatus) {
    document = currentStatus.document;
    acceptedAt = currentStatus.acceptedAt;
    status = currentStatus.accepted
        ? BuyerMobileTermsViewState.accepted
        : BuyerMobileTermsViewState.requiresAcceptance;
    notice = null;
  }

  void _setUnavailable(BuyerMobileTermsNotice nextMessage) {
    status = BuyerMobileTermsViewState.unavailable;
    acceptedAt = null;
    notice = nextMessage;
  }

  bool _isCurrent(int generation, String? identity) =>
      generation == _requestGeneration &&
      identity == _sessionKey &&
      identity == _keyFor(_access.snapshot);

  bool _acceptanceMatches(
    BuyerMobileTermsAcceptance acceptance,
    BuyerMobileTermsDocument currentDocument,
  ) =>
      acceptance.documentId == currentDocument.documentId &&
      acceptance.version == currentDocument.version &&
      acceptance.contentDigest == currentDocument.contentDigest;

  BuyerMobileTermsNotice _messageFor(Object error) =>
      error == BuyerMobileTermsFailure.accessDenied
      ? BuyerMobileTermsNotice.statusAccessDenied
      : BuyerMobileTermsNotice.termsUnavailable;

  static String? _keyFor(BuyerAccessSnapshot snapshot) {
    if (!snapshot.hasActiveBuyerScope) return null;
    final userId = snapshot.currentContext!.userId;
    if (userId == null || userId.isEmpty) return null;
    return '${snapshot.authorityEpoch}|$userId|'
        '${snapshot.currentContext!.authorityFingerprint}';
  }

  @override
  void dispose() {
    _requestGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}
