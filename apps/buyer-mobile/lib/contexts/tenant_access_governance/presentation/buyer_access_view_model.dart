import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../application/buyer_access_repository.dart';

final class BuyerAccessViewModel extends ChangeNotifier {
  BuyerAccessViewModel(this._repository) : snapshot = _repository.snapshot {
    _subscription = _repository.changes.listen((value) {
      snapshot = value;
      notifyListeners();
      refreshBiometricLoginStatus();
    });
    refreshBiometricLoginStatus();
  }

  final BuyerAccessRepository _repository;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;

  BuyerAccessSnapshot snapshot;
  BuyerBiometricLoginStatus biometricStatus = const BuyerBiometricLoginStatus();
  bool busy = false;
  String? message;
  int _biometricStatusRequest = 0;

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

  Future<void> lockForBiometricLogin() async {
    await _run(() async {
      await _repository.lockForBiometricLogin();
    });
  }

  Future<void> signInWithBiometrics() async {
    await _run(_repository.signInWithBiometrics);
  }

  Future<void> setBiometricLoginEnabled(bool enabled) async {
    await _run(() async {
      if (enabled) {
        await _repository.enableBiometricLogin();
      } else {
        await _repository.disableBiometricLogin();
      }
    });
    await refreshBiometricLoginStatus();
  }

  Future<void> refreshBiometricLoginStatus() async {
    final request = ++_biometricStatusRequest;
    try {
      final status = await _repository.biometricLoginStatus();
      if (request != _biometricStatusRequest) return;
      biometricStatus = status;
      notifyListeners();
    } catch (_) {
      if (request != _biometricStatusRequest) return;
      biometricStatus = const BuyerBiometricLoginStatus();
      notifyListeners();
    }
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
      refreshBiometricLoginStatus();
    }
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
