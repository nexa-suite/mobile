import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/catalog_repository.dart';

enum CatalogLoadStatus { idle, loading, current, unavailable }

final class CatalogViewModel extends ChangeNotifier {
  CatalogViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final CatalogRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  CatalogLoadStatus status = CatalogLoadStatus.idle;
  List<CatalogItemProjection> items = const [];
  String query = '';
  int page = 0;
  int totalItems = 0;
  int totalPages = 0;
  DateTime? updatedAt;
  String? message;

  Future<void> search(String value) => _load(query: value.trim(), page: 0);

  Future<void> refresh() => _load(query: query, page: page);

  Future<void> nextPage() {
    if (status != CatalogLoadStatus.current || page + 1 >= totalPages) {
      return Future.value();
    }
    return _load(query: query, page: page + 1);
  }

  Future<void> previousPage() {
    if (status != CatalogLoadStatus.current || page == 0) {
      return Future.value();
    }
    return _load(query: query, page: page - 1);
  }

  Future<void> _load({required String query, required int page}) async {
    final lease = _leaseKey;
    if (lease == null) return;
    final generation = ++_requestGeneration;
    final sameQuery = this.query == query;
    status = CatalogLoadStatus.loading;
    this.query = query;
    message = null;
    if (!sameQuery) {
      items = const [];
      totalItems = 0;
      totalPages = 0;
      updatedAt = null;
    }
    notifyListeners();

    try {
      final result = await _repository.list(query: query, page: page);
      if (!_isCurrent(generation, lease)) return;
      items = result.items;
      this.page = result.page;
      totalItems = result.totalItems;
      totalPages = result.totalPages;
      updatedAt = DateTime.now();
      status = CatalogLoadStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      status = CatalogLoadStatus.unavailable;
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = CatalogLoadStatus.unavailable;
      message = 'The catalog could not be refreshed.';
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
    status = CatalogLoadStatus.idle;
    items = const [];
    query = '';
    page = 0;
    totalItems = 0;
    totalPages = 0;
    updatedAt = null;
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
