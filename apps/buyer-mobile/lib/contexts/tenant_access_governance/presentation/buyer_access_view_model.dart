import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../application/buyer_access_repository.dart';

final class BuyerAccessViewModel extends ChangeNotifier {
  BuyerAccessViewModel(
    this._repository, {
    BuyerPasswordRecoveryRepository? passwordRecoveryRepository,
    BuyerBiometricSessionRepository? biometricSessionRepository,
    this._biometricAuthenticator,
  }) : _passwordRecoveryRepository =
           passwordRecoveryRepository ??
           _asPasswordRecoveryRepository(_repository),
       _passwordChangeRepository = _asPasswordChangeRepository(_repository),
       _sessionSecurityRepository = _asSessionSecurityRepository(_repository),
       _biometricSessionRepository =
           biometricSessionRepository ??
           _asBiometricSessionRepository(_repository),
       snapshot = _repository.snapshot {
    _subscription = _repository.changes.listen((value) {
      snapshot = value;
      notifyListeners();
    });
  }

  final BuyerAccessRepository _repository;
  final BuyerPasswordRecoveryRepository? _passwordRecoveryRepository;
  final BuyerPasswordChangeRepository? _passwordChangeRepository;
  final BuyerSessionSecurityRepository? _sessionSecurityRepository;
  final BuyerBiometricSessionRepository? _biometricSessionRepository;
  final BuyerBiometricAuthenticator? _biometricAuthenticator;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;

  BuyerAccessSnapshot snapshot;
  bool busy = false;
  bool biometricBusy = false;
  bool biometricSupported = false;
  bool biometricEnabled = false;
  bool biometricLoginAvailable = false;
  String? biometricMessage;
  String? message;

  Future<void> signIn(String identifier, String password) async {
    await _run(
      () => _repository.signIn(identifier: identifier, password: password),
    );
  }

  Future<BuyerPasswordResetRequestResult> requestPasswordReset(String email) =>
      _passwordRecoveryRepository?.requestPasswordReset(email) ??
      Future.value(BuyerPasswordResetRequestResult.unavailable);

  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    final repository = _passwordChangeRepository;
    if (repository == null) {
      throw StateError('Password change is not available.');
    }
    await repository.changePassword(
      currentPassword: currentPassword,
      newPassword: newPassword,
    );
  }

  Future<void> revokeOtherSessions() async {
    final repository = _sessionSecurityRepository;
    if (repository == null) {
      throw StateError('Session management is not available.');
    }
    await repository.revokeOtherSessions();
  }

  Future<void> refreshBiometricAvailability() async {
    final authenticator = _biometricAuthenticator;
    final repository = _biometricSessionRepository;
    if (authenticator == null || repository == null) return;
    try {
      final supported = await authenticator.isAvailable();
      final enabled = await repository.hasBiometricSession();
      biometricSupported = supported;
      biometricEnabled = enabled;
      biometricLoginAvailable = supported && enabled;
    } catch (_) {
      biometricSupported = false;
      biometricLoginAvailable = false;
    }
    notifyListeners();
  }

  Future<void> setBiometricUnlockEnabled(bool enabled) async {
    final authenticator = _biometricAuthenticator;
    final repository = _biometricSessionRepository;
    if (biometricBusy || authenticator == null || repository == null) return;
    biometricBusy = true;
    biometricMessage = null;
    notifyListeners();
    try {
      if (enabled) {
        biometricSupported = await authenticator.isAvailable();
        if (!biometricSupported) {
          biometricMessage = 'Este dispositivo no tiene biometría configurada.';
          return;
        }
        final accepted = await authenticator.authenticate(
          'Confirma tu identidad para activar el acceso biométrico de Nexa Buyer.',
        );
        if (!accepted) return;
      }
      await repository.setBiometricUnlockEnabled(enabled);
      biometricEnabled = enabled;
      biometricLoginAvailable = enabled && biometricSupported;
    } catch (_) {
      biometricMessage = 'No se pudo actualizar el acceso biométrico.';
    } finally {
      biometricBusy = false;
      notifyListeners();
    }
  }

  Future<void> signInWithBiometrics() async {
    final authenticator = _biometricAuthenticator;
    final repository = _biometricSessionRepository;
    if (busy || biometricBusy || authenticator == null || repository == null) {
      return;
    }
    biometricBusy = true;
    message = null;
    biometricMessage = null;
    notifyListeners();
    try {
      biometricSupported = await authenticator.isAvailable();
      biometricEnabled = await repository.hasBiometricSession();
      biometricLoginAvailable = biometricSupported && biometricEnabled;
      if (!biometricLoginAvailable) {
        biometricMessage = 'Activa el acceso biométrico desde Perfil después de iniciar sesión.';
        return;
      }
      final accepted = await authenticator.authenticate(
        'Confirma tu identidad para continuar con Nexa Buyer.',
      );
      if (!accepted) return;
      busy = true;
      final restored = await repository.restoreBiometricSession();
      if (!restored) {
        biometricEnabled = false;
        biometricLoginAvailable = false;
        biometricMessage = 'La sesión venció. Entra con tu contraseña y activa de nuevo la biometría en Perfil.';
      }
    } on NexaApiFailure catch (failure) {
      message = failure.userMessage;
    } catch (_) {
      biometricMessage =
          'No se pudo desbloquear la sesión. Inténtalo con tu contraseña.';
    } finally {
      busy = false;
      biometricBusy = false;
      notifyListeners();
    }
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

BuyerPasswordRecoveryRepository? _asPasswordRecoveryRepository(
  BuyerAccessRepository repository,
) => repository is BuyerPasswordRecoveryRepository
    ? repository as BuyerPasswordRecoveryRepository
    : null;

BuyerPasswordChangeRepository? _asPasswordChangeRepository(
  BuyerAccessRepository repository,
) => repository is BuyerPasswordChangeRepository
    ? repository as BuyerPasswordChangeRepository
    : null;

BuyerSessionSecurityRepository? _asSessionSecurityRepository(
  BuyerAccessRepository repository,
) => repository is BuyerSessionSecurityRepository
    ? repository as BuyerSessionSecurityRepository
    : null;

BuyerBiometricSessionRepository? _asBiometricSessionRepository(
  BuyerAccessRepository repository,
) => repository is BuyerBiometricSessionRepository
    ? repository as BuyerBiometricSessionRepository
    : null;
