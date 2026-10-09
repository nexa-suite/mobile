import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_wallet_repository.dart';

enum BuyerWalletViewStatus {
  idle,
  loading,
  current,
  notInitialized,
  unavailable,
  permissionDenied,
}

final class BuyerWalletViewModel extends ChangeNotifier {
  BuyerWalletViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const pageSize = 25;
  static const readPermission = 'payment.read';
  static const buyerRole = 'BUYER';

  final BuyerWalletRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerWalletViewStatus status = BuyerWalletViewStatus.idle;
  BuyerWalletProjection? wallet;
  String? message;

  bool get canRead => _hasReadAuthority(_access.snapshot);

  Future<void> refresh() => loadPage(0);

  Future<void> nextPage() {
    final current = wallet?.movements;
    if (status != BuyerWalletViewStatus.current ||
        current == null ||
        (current.page + 1) * current.size >= current.total) {
      return Future.value();
    }
    return loadPage(current.page + 1);
  }

  Future<void> previousPage() {
    final current = wallet?.movements;
    if (status != BuyerWalletViewStatus.current ||
        current == null ||
        current.page == 0) {
      return Future.value();
    }
    return loadPage(current.page - 1);
  }

  Future<void> loadPage(int page) async {
    final lease = _leaseKey;
    if (lease == null) {
      _clear(
        BuyerWalletViewStatus.unavailable,
        'Inicia sesión para consultar tu billetera.',
      );
      return;
    }
    if (!canRead) {
      _requestGeneration++;
      _clear(
        BuyerWalletViewStatus.permissionDenied,
        'No tienes permiso para consultar la billetera de Buyer.',
      );
      return;
    }
    if (page < 0) {
      _clear(
        BuyerWalletViewStatus.unavailable,
        'No se pudo abrir esta página de movimientos.',
      );
      return;
    }

    final generation = ++_requestGeneration;
    status = BuyerWalletViewStatus.loading;
    wallet = null;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.readCurrentWallet(page: page);
      if (!_isCurrent(generation, lease)) return;
      wallet = result;
      status = result.state == BuyerWalletState.active
          ? BuyerWalletViewStatus.current
          : BuyerWalletViewStatus.notInitialized;
      message = null;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        _clear(
          BuyerWalletViewStatus.permissionDenied,
          'No tienes permiso para consultar la billetera de Buyer.',
        );
        return;
      }
      _clear(
        BuyerWalletViewStatus.unavailable,
        failure.statusCode == 503
            ? 'La billetera no está disponible en este momento.'
            : failure.userMessage,
      );
      return;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      _clear(
        BuyerWalletViewStatus.unavailable,
        'No se pudo consultar la billetera.',
      );
      return;
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration &&
      lease == _leaseKey &&
      lease == _activeLease(_access.snapshot);

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = _activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    _clear(
      snapshot.isSignedIn && !_hasReadAuthority(snapshot)
          ? BuyerWalletViewStatus.permissionDenied
          : BuyerWalletViewStatus.idle,
      snapshot.isSignedIn && !_hasReadAuthority(snapshot)
          ? 'No tienes permiso para consultar la billetera de Buyer.'
          : null,
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _clear(
      BuyerWalletViewStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clear(BuyerWalletViewStatus status, String? message) {
    this.status = status;
    wallet = null;
    this.message = message;
    notifyListeners();
  }

  static bool _hasReadAuthority(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.roles.contains(buyerRole) &&
      snapshot.currentContext!.permissions.contains(readPermission);

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  static bool _isAuthorityFailure(NexaApiFailure failure) =>
      failure.statusCode == 401 || failure.code == 'ACCESS_CONTEXT_INVALID';

  @override
  void dispose() {
    _requestGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}
