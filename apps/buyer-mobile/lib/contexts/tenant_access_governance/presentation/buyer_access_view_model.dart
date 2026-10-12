import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_access_repository.dart';

final class BuyerAccessViewModel extends ChangeNotifier {
  BuyerAccessViewModel(this._repository) : snapshot = _repository.snapshot {
    _subscription = _repository.changes.listen((value) {
      snapshot = value;
      notifyListeners();
    });
  }

  final BuyerAccessRepository _repository;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;

  BuyerAccessSnapshot snapshot;
  bool busy = false;
  String? message;

  Future<void> signIn(String identifier, String password) async {
    await _run(
      () => _repository.signIn(identifier: identifier, password: password),
    );
  }

  Future<void> selectContext(String membershipId) async {
    await _run(() => _repository.selectContext(membershipId));
  }

  Future<void> signOut() async {
    await _run(_repository.signOut);
  }

  Future<void> _run(Future<void> Function() action) async {
    busy = true;
    message = null;
    notifyListeners();
    try {
      await action();
    } on NexaApiFailure catch (failure) {
      message = failure.userMessage;
    } catch (_) {
      message = 'Nexa could not complete the request. Try again.';
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
