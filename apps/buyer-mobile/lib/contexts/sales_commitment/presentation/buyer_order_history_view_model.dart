import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_order_history_repository.dart';

final class BuyerOrderHistoryViewModel extends ChangeNotifier {
  BuyerOrderHistoryViewModel(this._repository, this._access, this.orderId) {
    _lease = _scope;
    _subscription = _access.changes.listen((_) {
      if (_lease == _scope) return;
      _lease = _scope;
      _generation++;
      events = const [];
      busy = false;
      message = null;
      notifyListeners();
    });
  }
  final BuyerOrderHistoryRepository _repository;
  final BuyerAccessRepository _access;
  final String orderId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  int _generation = 0;
  List<BuyerOrderEvent> events = const [];
  bool busy = false;
  String? message;
  String? get _scope =>
      _access.snapshot.hasActiveBuyerScope &&
          _access.snapshot.currentContext!.permissions.contains(
            'buyer.order.read',
          )
      ? '${_access.snapshot.authorityEpoch}|${_access.snapshot.currentContext!.authorityFingerprint}'
      : null;
  Future<void> load() async {
    if (busy || _scope == null) return;
    final generation = ++_generation, lease = _scope;
    bool current() => generation == _generation && lease == _scope;
    busy = true;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.history(orderId);
      if (!current()) return;
      events = result;
    } catch (error) {
      if (!current()) return;
      if (error is NexaApiFailure && error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      if (error is NexaApiFailure && {403, 404}.contains(error.statusCode)) {
        events = const [];
      }
      message = 'No pudimos actualizar el historial. Vuelve a consultarlo.';
    } finally {
      if (current()) {
        busy = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _generation++;
    _subscription.cancel();
    super.dispose();
  }
}
