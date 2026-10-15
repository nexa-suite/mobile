import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_orders_repository.dart';

enum BuyerOrdersLoadStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BuyerOrdersViewModel extends ChangeNotifier {
  BuyerOrdersViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final BuyerOrdersRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerOrdersLoadStatus status = BuyerOrdersLoadStatus.idle;
  List<BuyerOrderProjection> items = const [];
  int page = 0;
  int size = 25;
  int total = 0;
  String? message;

  static const readPermission = 'buyer.order.read';

  Future<void> refresh() => _load(page);

  Future<void> nextPage() {
    if (status != BuyerOrdersLoadStatus.current || (page + 1) * size >= total) {
      return Future.value();
    }
    return _load(page + 1);
  }

  Future<void> previousPage() {
    if (status != BuyerOrdersLoadStatus.current || page == 0) {
      return Future.value();
    }
    return _load(page - 1);
  }

  Future<void> _load(int requestedPage) async {
    final snapshot = _access.snapshot;
    final lease = _leaseKey;
    if (lease == null) {
      _requestGeneration++;
      status = BuyerOrdersLoadStatus.unavailable;
      items = const [];
      message = 'Inicia sesión para consultar tus pedidos.';
      notifyListeners();
      return;
    }
    if (!snapshot.hasActiveBuyerScope ||
        !snapshot.currentContext!.permissions.contains(readPermission)) {
      _requestGeneration++;
      status = BuyerOrdersLoadStatus.permissionDenied;
      items = const [];
      page = 0;
      total = 0;
      message = 'No tienes permiso para consultar tus pedidos.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    status = BuyerOrdersLoadStatus.loading;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.list(page: requestedPage);
      if (!_isCurrent(generation, lease)) return;
      items = result.items;
      page = result.page;
      size = result.size;
      total = result.total;
      status = BuyerOrdersLoadStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _clear(BuyerOrdersLoadStatus.unavailable, null);
        _access.invalidateLocalSession();
        return;
      }
      if (failure.code == 'ACCESS_CONTEXT_INVALID') {
        _clear(
          BuyerOrdersLoadStatus.unavailable,
          'El contexto activo cambió. Inicia sesión nuevamente.',
        );
        _access.invalidateLocalSession();
        return;
      }
      _clear(
        failure.statusCode == 403
            ? BuyerOrdersLoadStatus.permissionDenied
            : BuyerOrdersLoadStatus.unavailable,
        failure.statusCode == 403
            ? 'No tienes permiso para consultar tus pedidos.'
            : failure.userMessage,
      );
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      _clear(
        BuyerOrdersLoadStatus.unavailable,
        'No se pudieron actualizar tus pedidos.',
      );
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
    if (!_hasPermission(snapshot)) {
      _clear(
        BuyerOrdersLoadStatus.permissionDenied,
        'No tienes permiso para consultar tus pedidos.',
      );
      return false;
    }
    return true;
  }

  static bool _hasPermission(BuyerAccessSnapshot snapshot) =>
      snapshot.hasActiveBuyerScope &&
      snapshot.currentContext!.permissions.contains(readPermission);

  void _clear(BuyerOrdersLoadStatus nextStatus, String? nextMessage) {
    status = nextStatus;
    items = const [];
    page = 0;
    size = 25;
    total = 0;
    message = nextMessage;
    notifyListeners();
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextScope = _activeLease(snapshot);
    if (nextScope == _leaseKey) return;
    _leaseKey = nextScope;
    _requestGeneration++;
    _clear(
      snapshot.isSignedIn && !_hasPermission(snapshot)
          ? BuyerOrdersLoadStatus.permissionDenied
          : BuyerOrdersLoadStatus.idle,
      snapshot.isSignedIn && !_hasPermission(snapshot)
          ? 'No tienes permiso para consultar tus pedidos.'
          : null,
    );
  }

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.hasActiveBuyerScope
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}

enum BuyerOrderDetailStatus { loading, current, unavailable, permissionDenied }

final class BuyerOrderDetailViewModel extends ChangeNotifier {
  BuyerOrderDetailViewModel(this._repository, this._access, this.orderId)
    : _leaseKey = BuyerOrdersViewModel._activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final BuyerOrdersRepository _repository;
  final BuyerAccessRepository _access;
  final String orderId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerOrderDetailStatus status = BuyerOrderDetailStatus.loading;
  BuyerOrderProjection? order;
  String? message;

  Future<void> load() async {
    final lease = _leaseKey;
    if (lease == null) {
      status = BuyerOrderDetailStatus.unavailable;
      order = null;
      notifyListeners();
      return;
    }
    final snapshot = _access.snapshot;
    if (!BuyerOrdersViewModel._hasPermission(snapshot)) {
      _requestGeneration++;
      status = BuyerOrderDetailStatus.permissionDenied;
      order = null;
      message = 'No tienes permiso para consultar este pedido.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    status = BuyerOrderDetailStatus.loading;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.detail(orderId);
      if (!_isCurrent(generation, lease)) return;
      order = result;
      status = BuyerOrderDetailStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        order = null;
        status = BuyerOrderDetailStatus.unavailable;
        message = null;
        notifyListeners();
        _access.invalidateLocalSession();
        return;
      }
      if (failure.code == 'ACCESS_CONTEXT_INVALID') {
        order = null;
        status = BuyerOrderDetailStatus.unavailable;
        message = 'El contexto activo cambió. Inicia sesión nuevamente.';
        notifyListeners();
        _access.invalidateLocalSession();
        return;
      }
      order = null;
      status = failure.statusCode == 403
          ? BuyerOrderDetailStatus.permissionDenied
          : BuyerOrderDetailStatus.unavailable;
      message = failure.statusCode == 403
          ? 'No tienes permiso para consultar este pedido.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      order = null;
      status = BuyerOrderDetailStatus.unavailable;
      message = 'Este pedido no está disponible.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) {
    if (generation != _requestGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != BuyerOrdersViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    if (!BuyerOrdersViewModel._hasPermission(snapshot)) {
      order = null;
      status = BuyerOrderDetailStatus.permissionDenied;
      message = 'No tienes permiso para consultar este pedido.';
      notifyListeners();
      return false;
    }
    return true;
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextScope = BuyerOrdersViewModel._activeLease(snapshot);
    if (nextScope == _leaseKey) return;
    _leaseKey = nextScope;
    _requestGeneration++;
    order = null;
    status =
        snapshot.isSignedIn && !BuyerOrdersViewModel._hasPermission(snapshot)
        ? BuyerOrderDetailStatus.permissionDenied
        : BuyerOrderDetailStatus.unavailable;
    message =
        snapshot.isSignedIn && !BuyerOrdersViewModel._hasPermission(snapshot)
        ? 'No tienes permiso para consultar este pedido.'
        : 'El contexto activo cambió. Vuelve a abrir este pedido.';
    notifyListeners();
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
