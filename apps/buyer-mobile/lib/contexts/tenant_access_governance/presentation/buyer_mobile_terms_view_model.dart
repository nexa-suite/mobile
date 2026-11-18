import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
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
  String? message;

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
    message = null;
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
      if (error is NexaApiFailure && error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      if (previouslyAccepted && _isRetryableTransportFailure(error)) {
        document = previousDocument;
        status = BuyerMobileTermsViewState.accepted;
        acceptedAt = previousAcceptedAt;
        message = 'No pudimos actualizar los términos. Se conserva la aceptación confirmada en esta sesión.';
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
    message = null;
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
          'No se confirmó la aceptación. Revisa el estado antes de volver a intentarlo.',
        );
        return;
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, identity)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      if (failure.statusCode == 403) {
        _setUnavailable(
          'No se pudo verificar tu acceso para aceptar los términos. Actualiza el estado o vuelve a iniciar sesión.',
        );
      } else {
        await _refreshAfterPost(
          generation,
          identity,
          failure.statusCode == 409
              ? 'Los términos cambiaron. Revisa la versión vigente antes de continuar.'
              : 'Comprobamos el estado para confirmar si se registró la aceptación.',
        );
        return;
      }
    } catch (_) {
      if (!_isCurrent(generation, identity)) return;
      await _refreshAfterPost(
        generation,
        identity,
        'Comprobamos el estado para confirmar si se registró la aceptación.',
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
    message = null;
    status = BuyerMobileTermsViewState.idle;
    notifyListeners();
    unawaited(refresh());
  }

  Future<void> _refreshAfterPost(
    int generation,
    String identity,
    String outcomeMessage,
  ) async {
    if (!_isCurrent(generation, identity)) return;
    status = BuyerMobileTermsViewState.loading;
    message = outcomeMessage;
    notifyListeners();
    try {
      final currentStatus = await _repository.fetchCurrentStatus();
      if (!_isCurrent(generation, identity)) return;
      final previousVersion = document?.version;
      _applyCurrentStatus(currentStatus);
      if (status != BuyerMobileTermsViewState.accepted) {
        message = currentStatus.document.version != previousVersion
            ? 'Hay una nueva versión. Revísala antes de continuar.'
            : 'No se registró la aceptación. Puedes volver a intentarlo después de revisar esta versión.';
      }
    } catch (error) {
      if (!_isCurrent(generation, identity)) return;
      if (error is NexaApiFailure && error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      _setUnavailable(
        'No se pudo confirmar el resultado. Actualiza el estado antes de intentar de nuevo.',
      );
    }
    notifyListeners();
  }

  void _applyCurrentStatus(BuyerMobileTermsStatus currentStatus) {
    document = currentStatus.document;
    acceptedAt = currentStatus.acceptedAt;
    status = currentStatus.accepted
        ? BuyerMobileTermsViewState.accepted
        : BuyerMobileTermsViewState.requiresAcceptance;
    message = null;
  }

  void _setUnavailable(String nextMessage) {
    status = BuyerMobileTermsViewState.unavailable;
    acceptedAt = null;
    message = nextMessage;
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

  String _messageFor(Object error) =>
      error is NexaApiFailure && error.statusCode == 403
      ? 'No se pudo consultar el estado de los términos con este acceso.'
      : 'No se pudieron cargar los términos vigentes. Inténtalo de nuevo.';

  bool _isRetryableTransportFailure(Object error) =>
      error is NexaApiFailure && error.statusCode == null && error.retryable;

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
