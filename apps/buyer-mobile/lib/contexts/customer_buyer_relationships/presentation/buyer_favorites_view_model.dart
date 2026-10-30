import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_favorites_repository.dart';

enum BuyerFavoritesStatus { idle, loading, current, unavailable }

final class BuyerFavoritesViewModel extends ChangeNotifier {
  BuyerFavoritesViewModel(this._repository, this._access) {
    _lease = _currentLease;
    _subscription = _access.changes.listen((_) {
      if (_lease == _currentLease) return;
      _lease = _currentLease;
      _generation++;
      items = const [];
      _known.clear();
      _statusLoading.clear();
      _pending = null;
      busy = false;
      message = null;
      status = BuyerFavoritesStatus.idle;
      page = 0;
      totalPages = 0;
      notifyListeners();
    });
  }
  final BuyerFavoritesRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  int _generation = 0;
  bool _disposed = false;
  final Map<String, bool> _known = {};
  final Set<String> _statusLoading = {};
  ({String sku, bool save})? _pending;
  List<BuyerFavorite> items = const [];
  BuyerFavoritesStatus status = BuyerFavoritesStatus.idle;
  bool busy = false;
  int page = 0;
  int totalPages = 0;
  String? message;
  bool messageIsError = false;

  String? get _currentLease => _access.snapshot.hasActiveBuyerScope
      ? '${_access.snapshot.authorityEpoch}|${_access.snapshot.currentContext!.authorityFingerprint}'
      : null;
  bool get permitted =>
      _currentLease != null &&
      _access.snapshot.currentContext!.permissions.contains('catalog.read') &&
      _access.snapshot.currentContext!.permissions.contains('buyer.sales.read');
  bool get _canWrite =>
      permitted &&
      _access.snapshot.currentContext!.permissions.contains(
        'buyer.sales.write',
      );
  bool get canRetry => !busy && _pending != null && _canWrite;
  bool get canManage => _canWrite;
  bool get canBrowse =>
      permitted &&
      !busy &&
      _pending == null &&
      status != BuyerFavoritesStatus.loading;
  bool get canEdit => _canWrite && canBrowse;
  bool? savedStatus(String sku) => _known[sku];
  bool checkingStatus(String sku) => _statusLoading.contains(sku);

  Future<void> loadStatus(String sku) async {
    if (!permitted ||
        _statusLoading.contains(sku) ||
        busy ||
        _pending != null) {
      return;
    }
    final generation = _generation, lease = _currentLease;
    _statusLoading.add(sku);
    _known.remove(sku);
    notifyListeners();
    try {
      final saved = await _repository.isSaved(sku);
      if (!_current(generation, lease)) return;
      _known[sku] = saved;
      if (messageIsError) message = null;
    } catch (error) {
      if (!_current(generation, lease)) return;
      _known.remove(sku);
      _failure(error);
    } finally {
      if (!_disposed && lease == _currentLease) {
        _statusLoading.remove(sku);
        notifyListeners();
      }
    }
  }

  bool _current(int generation, String? lease) =>
      !_disposed && generation == _generation && lease == _currentLease;

  Future<void> load({int page = 0}) async {
    if (!permitted || busy || _pending != null || page < 0) return;
    final generation = ++_generation;
    final lease = _lease;
    status = BuyerFavoritesStatus.loading;
    _known.clear();
    notifyListeners();
    try {
      var result = await _repository.list(page: page);
      if (!_current(generation, lease)) return;
      if (result.page > 0 && result.page >= result.totalPages) {
        result = await _repository.list(page: 0);
        if (!_current(generation, lease)) return;
      }
      items = result.items;
      _known.clear();
      for (final item in items) {
        _known[item.sellableSkuId] = true;
      }
      if (messageIsError) message = null;
      this.page = result.page;
      totalPages = result.totalPages;
      status = BuyerFavoritesStatus.current;
    } catch (error) {
      if (!_current(generation, lease)) return;
      status = BuyerFavoritesStatus.unavailable;
      items = const [];
      _failure(error);
    }
    if (_current(generation, lease)) notifyListeners();
  }

  Future<void> setSaved(String sku, bool save) async {
    if (!_canWrite ||
        busy ||
        status == BuyerFavoritesStatus.loading ||
        (_pending != null && _pending != (sku: sku, save: save))) {
      return;
    }
    final generation = ++_generation;
    final lease = _lease;
    var confirmed = false;
    _pending = (sku: sku, save: save);
    _known.remove(sku);
    busy = true;
    message = null;
    notifyListeners();
    try {
      if (save) {
        await _repository.save(sku);
      } else {
        await _repository.remove(sku);
      }
      if (!_current(generation, lease)) return;
      _pending = null;
      confirmed = true;
      if (save) {
        _known[sku] = true;
      } else {
        _known[sku] = false;
        items = items.where((item) => item.sellableSkuId != sku).toList();
      }
      message = save
          ? 'Producto guardado en favoritos.'
          : 'Producto retirado de favoritos.';
      messageIsError = false;
    } catch (error) {
      if (!_current(generation, lease)) return;
      if (error is NexaApiFailure &&
          {400, 403, 404, 409, 422}.contains(error.statusCode)) {
        _pending = null;
      }
      _failure(error);
    } finally {
      if (_current(generation, lease)) {
        busy = false;
        notifyListeners();
      }
    }
    if (_current(generation, lease) &&
        confirmed &&
        !save &&
        status == BuyerFavoritesStatus.current &&
        items.isEmpty) {
      await load(page: page > 0 ? page - 1 : 0);
    }
  }

  Future<void> retry() async {
    final pending = _pending;
    if (canRetry && pending != null) await setSaved(pending.sku, pending.save);
  }

  void _failure(Object error) {
    if (error is NexaApiFailure && error.statusCode == 401) {
      _access.invalidateLocalSession();
      return;
    }
    messageIsError = true;
    message = switch (error) {
      NexaApiFailure(statusCode: 404) =>
        'Favoritos o este producto no están disponibles en este momento.',
      NexaApiFailure(statusCode: 403) =>
        'Tu acceso actual no permite consultar o cambiar favoritos.',
      _ => 'No pudimos confirmar tus favoritos. Vuelve a intentarlo.',
    };
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _subscription.cancel();
    super.dispose();
  }
}
