import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_orders_repository.dart';

enum BuyerOrdersLoadStatus { idle, loading, current, unavailable }

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
    final lease = _leaseKey;
    if (lease == null) return;
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
        _access.invalidateLocalSession();
        return;
      }
      status = BuyerOrdersLoadStatus.unavailable;
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = BuyerOrdersLoadStatus.unavailable;
      message = 'Orders could not be refreshed.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration && lease == _leaseKey;

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextScope = _activeLease(snapshot);
    if (nextScope == _leaseKey) return;
    _leaseKey = nextScope;
    _requestGeneration++;
    status = BuyerOrdersLoadStatus.idle;
    items = const [];
    page = 0;
    size = 25;
    total = 0;
    message = null;
    notifyListeners();
  }

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

enum BuyerOrderDetailStatus { loading, current, unavailable }

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
        _access.invalidateLocalSession();
        return;
      }
      order = null;
      status = BuyerOrderDetailStatus.unavailable;
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      order = null;
      status = BuyerOrderDetailStatus.unavailable;
      message = 'This order is not available.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration && lease == _leaseKey;

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextScope = BuyerOrdersViewModel._activeLease(snapshot);
    if (nextScope == _leaseKey) return;
    _leaseKey = nextScope;
    _requestGeneration++;
    order = null;
    status = BuyerOrderDetailStatus.unavailable;
    message = 'Sign in again to view this order.';
    notifyListeners();
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
