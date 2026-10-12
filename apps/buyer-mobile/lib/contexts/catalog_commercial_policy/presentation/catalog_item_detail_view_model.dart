import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/catalog_repository.dart';

enum CatalogItemDetailStatus { loading, current, unavailable }

final class CatalogItemDetailViewModel extends ChangeNotifier {
  CatalogItemDetailViewModel(this._catalog, this._access, this.catalogItemId)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final CatalogRepository _catalog;
  final BuyerAccessRepository _access;
  final String catalogItemId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  CatalogItemDetailStatus status = CatalogItemDetailStatus.loading;
  CatalogItemProjection? item;
  String? message;

  Future<void> load() async {
    final lease = _leaseKey;
    if (lease == null) {
      status = CatalogItemDetailStatus.unavailable;
      message = 'Sign in again to view this product.';
      notifyListeners();
      return;
    }

    final generation = ++_requestGeneration;
    status = CatalogItemDetailStatus.loading;
    message = null;
    notifyListeners();
    try {
      final result = await _catalog.detail(catalogItemId);
      if (!_isCurrent(generation, lease)) return;
      item = result;
      status = CatalogItemDetailStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      item = null;
      status = CatalogItemDetailStatus.unavailable;
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      item = null;
      status = CatalogItemDetailStatus.unavailable;
      message = 'This product is not available right now.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration && lease == _leaseKey;

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = _activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    item = null;
    status = CatalogItemDetailStatus.unavailable;
    message = nextLease == null
        ? 'Sign in again to view this product.'
        : 'Your access context changed. Refresh this product to continue.';
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
