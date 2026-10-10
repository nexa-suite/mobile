import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/credit_exposure_repository.dart';

enum BuyerCreditExposureStatus { idle, loading, current, unavailable }

enum BuyerReceivablesStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BuyerCreditExposureViewModel extends ChangeNotifier {
  BuyerCreditExposureViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final BuyerCreditExposureRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerCreditExposureStatus status = BuyerCreditExposureStatus.idle;
  BuyerCreditExposureProjection? exposure;
  String? message;
  BuyerReceivablesStatus receivablesStatus = BuyerReceivablesStatus.idle;
  List<BuyerReceivableProjection> receivables = const [];
  int receivablesPage = 0;
  int receivablesSize = 25;
  int receivablesTotal = 0;
  String? receivablesMessage;

  static const readPermission = 'payment.read';

  Future<void> refresh() async {
    final lease = _leaseKey;
    if (lease == null) {
      _clearAll(
        BuyerCreditExposureStatus.unavailable,
        'Inicia sesión para consultar tu crédito.',
        BuyerReceivablesStatus.unavailable,
        'Inicia sesión para consultar tus cuentas por cobrar.',
      );
      return;
    }
    if (!_hasReadPermission(_access.snapshot)) {
      _requestGeneration++;
      _clearAll(
        BuyerCreditExposureStatus.unavailable,
        'No tienes permiso para consultar tu crédito.',
        BuyerReceivablesStatus.permissionDenied,
        'No tienes permiso para consultar tus cuentas por cobrar.',
      );
      return;
    }
    final generation = ++_requestGeneration;
    status = BuyerCreditExposureStatus.loading;
    message = null;
    receivablesStatus = BuyerReceivablesStatus.loading;
    receivables = const [];
    receivablesPage = 0;
    receivablesTotal = 0;
    receivablesMessage = null;
    notifyListeners();
    try {
      final result = await _repository.readCurrentBuyerExposure(
        currency: 'PEN',
      );
      if (!_isCurrent(generation, lease)) return;
      exposure = result;
      status = BuyerCreditExposureStatus.current;
      notifyListeners();
      await _loadReceivablesPage(0, generation: generation, lease: lease);
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      _clearAll(
        BuyerCreditExposureStatus.unavailable,
        failure.userMessage,
        failure.statusCode == 403
            ? BuyerReceivablesStatus.permissionDenied
            : BuyerReceivablesStatus.unavailable,
        failure.statusCode == 403
            ? 'No tienes permiso para consultar tus cuentas por cobrar.'
            : 'No se pudieron consultar tus cuentas por cobrar.',
      );
      return;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      _clearAll(
        BuyerCreditExposureStatus.unavailable,
        'No se pudo actualizar la información de crédito.',
        BuyerReceivablesStatus.unavailable,
        'No se pudieron consultar tus cuentas por cobrar.',
      );
      return;
    }
    notifyListeners();
  }

  Future<void> nextReceivablesPage() {
    if (receivablesStatus != BuyerReceivablesStatus.current ||
        (receivablesPage + 1) * receivablesSize >= receivablesTotal) {
      return Future.value();
    }
    return _requestReceivablesPage(receivablesPage + 1);
  }

  Future<void> previousReceivablesPage() {
    if (receivablesStatus != BuyerReceivablesStatus.current ||
        receivablesPage == 0) {
      return Future.value();
    }
    return _requestReceivablesPage(receivablesPage - 1);
  }

  Future<void> _requestReceivablesPage(int page) async {
    final lease = _leaseKey;
    if (lease == null || exposure == null) return;
    if (!_hasReadPermission(_access.snapshot)) {
      _requestGeneration++;
      receivablesStatus = BuyerReceivablesStatus.permissionDenied;
      receivables = const [];
      receivablesTotal = 0;
      receivablesMessage =
          'No tienes permiso para consultar tus cuentas por cobrar.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    receivablesStatus = BuyerReceivablesStatus.loading;
    receivables = const [];
    receivablesMessage = null;
    notifyListeners();
    await _loadReceivablesPage(page, generation: generation, lease: lease);
    if (_isCurrent(generation, lease)) notifyListeners();
  }

  Future<void> _loadReceivablesPage(
    int page, {
    required int generation,
    required String lease,
  }) async {
    try {
      final result = await _repository.listBuyerReceivables(page: page);
      if (!_isCurrent(generation, lease)) return;
      final activeAccountId = exposure?.clientAccountId;
      if (activeAccountId == null ||
          result.items.any(
            (receivable) => receivable.clientAccountId != activeAccountId,
          )) {
        receivables = const [];
        receivablesTotal = 0;
        receivablesStatus = BuyerReceivablesStatus.unavailable;
        receivablesMessage = 'La cuenta de las cuentas por cobrar no coincide con el acceso activo.';
        return;
      }
      receivables = result.items;
      receivablesPage = result.page;
      receivablesSize = result.size;
      receivablesTotal = result.total;
      receivablesStatus = BuyerReceivablesStatus.current;
      receivablesMessage = null;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      receivables = const [];
      receivablesTotal = 0;
      receivablesStatus = failure.statusCode == 403
          ? BuyerReceivablesStatus.permissionDenied
          : BuyerReceivablesStatus.unavailable;
      receivablesMessage = failure.statusCode == 403
          ? 'No tienes permiso para consultar tus cuentas por cobrar.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      receivables = const [];
      receivablesTotal = 0;
      receivablesStatus = BuyerReceivablesStatus.unavailable;
      receivablesMessage = 'No se pudieron consultar tus cuentas por cobrar.';
    }
  }

  bool _isCurrent(int generation, String lease) {
    if (generation != _requestGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != _activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = _activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    _clearAll(
      snapshot.isSignedIn && !_hasReadPermission(snapshot)
          ? BuyerCreditExposureStatus.unavailable
          : BuyerCreditExposureStatus.idle,
      snapshot.isSignedIn && !_hasReadPermission(snapshot)
          ? 'No tienes permiso para consultar tu crédito.'
          : null,
      snapshot.isSignedIn && !_hasReadPermission(snapshot)
          ? BuyerReceivablesStatus.permissionDenied
          : BuyerReceivablesStatus.idle,
      snapshot.isSignedIn && !_hasReadPermission(snapshot)
          ? 'No tienes permiso para consultar tus cuentas por cobrar.'
          : null,
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _clearAll(
      BuyerCreditExposureStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
      BuyerReceivablesStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clearAll(
    BuyerCreditExposureStatus exposureStatus,
    String? exposureMessage,
    BuyerReceivablesStatus receivablesStatus,
    String? receivablesMessage,
  ) {
    status = exposureStatus;
    exposure = null;
    message = exposureMessage;
    this.receivablesStatus = receivablesStatus;
    receivables = const [];
    receivablesPage = 0;
    receivablesSize = 25;
    receivablesTotal = 0;
    this.receivablesMessage = receivablesMessage;
    notifyListeners();
  }

  static bool _hasReadPermission(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.permissions.contains(readPermission);

  static bool _isAuthorityFailure(NexaApiFailure failure) =>
      failure.statusCode == 401 || failure.code == 'ACCESS_CONTEXT_INVALID';

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
