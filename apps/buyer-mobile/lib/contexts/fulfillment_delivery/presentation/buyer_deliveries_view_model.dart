import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_deliveries_repository.dart';

enum BuyerDeliveriesStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BuyerDeliveriesViewModel extends ChangeNotifier {
  BuyerDeliveriesViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const pageSize = 25;
  static const readPermission = 'buyer.tracking.read';

  final BuyerDeliveriesRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerDeliveriesStatus status = BuyerDeliveriesStatus.idle;
  List<BuyerDeliveryProjection> items = const [];
  int page = 0;
  int total = 0;
  String? message;

  Future<void> refresh() => _load(page);

  Future<void> nextPage() {
    if (status != BuyerDeliveriesStatus.current ||
        (page + 1) * pageSize >= total) {
      return Future.value();
    }
    return _load(page + 1);
  }

  Future<void> previousPage() {
    if (status != BuyerDeliveriesStatus.current || page == 0) {
      return Future.value();
    }
    return _load(page - 1);
  }

  Future<void> _load(int requestedPage) async {
    final lease = _leaseKey;
    if (lease == null) {
      _clear(
        BuyerDeliveriesStatus.unavailable,
        'Inicia sesión para consultar tus entregas.',
      );
      return;
    }
    if (!_hasPermission(_access.snapshot)) {
      _requestGeneration++;
      _clear(
        BuyerDeliveriesStatus.permissionDenied,
        'No tienes permiso para consultar el seguimiento de entregas.',
      );
      return;
    }
    if (requestedPage < 0) {
      _clear(
        BuyerDeliveriesStatus.unavailable,
        'No se pudo abrir esta página de entregas.',
      );
      return;
    }

    final generation = ++_requestGeneration;
    status = BuyerDeliveriesStatus.loading;
    items = const [];
    total = 0;
    page = requestedPage;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.list(page: requestedPage);
      if (!_isCurrent(generation, lease)) return;
      items = result.items;
      page = result.page;
      total = result.total;
      status = BuyerDeliveriesStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401 ||
          failure.code == 'ACCESS_CONTEXT_INVALID') {
        _invalidateCurrentAuthority();
        return;
      }
      status = failure.statusCode == 403
          ? BuyerDeliveriesStatus.permissionDenied
          : BuyerDeliveriesStatus.unavailable;
      message = failure.statusCode == 403
          ? 'No tienes permiso para consultar el seguimiento de entregas.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = BuyerDeliveriesStatus.unavailable;
      message = 'No se pudieron consultar tus entregas.';
    }
    notifyListeners();
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
    _clear(
      snapshot.isSignedIn && !_hasPermission(snapshot)
          ? BuyerDeliveriesStatus.permissionDenied
          : BuyerDeliveriesStatus.idle,
      snapshot.isSignedIn && !_hasPermission(snapshot)
          ? 'No tienes permiso para consultar el seguimiento de entregas.'
          : null,
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _clear(
      BuyerDeliveriesStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clear(BuyerDeliveriesStatus nextStatus, String? nextMessage) {
    status = nextStatus;
    items = const [];
    page = 0;
    total = 0;
    message = nextMessage;
    notifyListeners();
  }

  static bool _hasPermission(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.permissions.contains(readPermission);

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  @override
  void dispose() {
    _requestGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}

enum BuyerDeliveryDetailStatus {
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BuyerDeliveryDetailViewModel extends ChangeNotifier {
  BuyerDeliveryDetailViewModel(this._repository, this._access, this.dispatchId)
    : _leaseKey = BuyerDeliveriesViewModel._activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final BuyerDeliveriesRepository _repository;
  final BuyerAccessRepository _access;
  final String dispatchId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerDeliveryDetailStatus status = BuyerDeliveryDetailStatus.loading;
  BuyerDeliveryProjection? delivery;
  List<BuyerDeliveryEventProjection> events = const [];
  String? message;

  Future<void> refresh() => load();

  Future<void> load() async {
    final lease = _leaseKey;
    if (lease == null) {
      _clear(
        BuyerDeliveryDetailStatus.unavailable,
        'Inicia sesión para consultar esta entrega.',
      );
      return;
    }
    if (!BuyerDeliveriesViewModel._hasPermission(_access.snapshot)) {
      _requestGeneration++;
      _clear(
        BuyerDeliveryDetailStatus.permissionDenied,
        'No tienes permiso para consultar el seguimiento de entregas.',
      );
      return;
    }
    final generation = ++_requestGeneration;
    status = BuyerDeliveryDetailStatus.loading;
    delivery = null;
    events = const [];
    message = null;
    notifyListeners();
    try {
      final result = await _repository.detail(dispatchId);
      if (!_isCurrent(generation, lease)) return;
      delivery = result.delivery;
      events = result.events;
      status = BuyerDeliveryDetailStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401 ||
          failure.code == 'ACCESS_CONTEXT_INVALID') {
        _invalidateCurrentAuthority();
        return;
      }
      status = failure.statusCode == 403
          ? BuyerDeliveryDetailStatus.permissionDenied
          : BuyerDeliveryDetailStatus.unavailable;
      message = failure.statusCode == 403
          ? 'No tienes permiso para consultar esta entrega.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = BuyerDeliveryDetailStatus.unavailable;
      message = 'Esta entrega no está disponible.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) {
    if (generation != _requestGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != BuyerDeliveriesViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = BuyerDeliveriesViewModel._activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    _clear(
      snapshot.isSignedIn && !BuyerDeliveriesViewModel._hasPermission(snapshot)
          ? BuyerDeliveryDetailStatus.permissionDenied
          : BuyerDeliveryDetailStatus.unavailable,
      snapshot.isSignedIn && !BuyerDeliveriesViewModel._hasPermission(snapshot)
          ? 'No tienes permiso para consultar esta entrega.'
          : 'El contexto activo cambió. Vuelve a abrir esta entrega.',
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _clear(
      BuyerDeliveryDetailStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clear(BuyerDeliveryDetailStatus nextStatus, String? nextMessage) {
    status = nextStatus;
    delivery = null;
    events = const [];
    message = nextMessage;
    notifyListeners();
  }

  @override
  void dispose() {
    _requestGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}
