import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../application/buyer_password_recovery_repository.dart';

final class BuyerPasswordRecoveryViewModel extends ChangeNotifier {
  BuyerPasswordRecoveryViewModel(this._repository);

  final BuyerPasswordRecoveryRepository _repository;

  bool busy = false;
  bool requestSent = false;
  bool passwordReset = false;
  String? message;

  Future<void> requestReset(String email) async {
    await _run(() => _repository.requestReset(email: email), () {
      requestSent = true;
    });
  }

  Future<void> resetPassword({
    required String token,
    required String newPassword,
  }) async {
    await _run(
      () => _repository.resetPassword(token: token, newPassword: newPassword),
      () {
        passwordReset = true;
      },
    );
  }

  void resetRequestState() {
    requestSent = false;
    passwordReset = false;
    message = null;
    notifyListeners();
  }

  Future<void> _run(
    Future<void> Function() action,
    VoidCallback onSuccess,
  ) async {
    if (busy) return;
    busy = true;
    message = null;
    notifyListeners();
    try {
      await action();
      onSuccess();
    } on NexaApiFailure catch (failure) {
      message = failure.userMessage;
    } catch (_) {
      message = 'No pudimos completar la solicitud. Inténtalo de nuevo.';
    } finally {
      busy = false;
      notifyListeners();
    }
  }
}
