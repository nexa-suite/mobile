import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../application/buyer_access_repository.dart';
import '../application/buyer_profile_repository.dart';

final class BuyerProfileViewModel extends ChangeNotifier {
  BuyerProfileViewModel(this._repository, this._access) {
    _lease = _currentLease;
    _subscription = _access.changes.listen((_) {
      if (_lease == _currentLease) return;
      _lease = _currentLease;
      _generation++;
      profile = null;
      busy = false;
      mustReload = false;
      message = null;
      notifyListeners();
    });
  }
  final BuyerProfileRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  int _generation = 0;
  BuyerProfile? profile;
  bool busy = false, mustReload = false, messageIsError = false;
  String? message;
  String? get _currentLease => _access.snapshot.hasActiveBuyerScope
      ? '${_access.snapshot.authorityEpoch}|${_access.snapshot.currentContext!.authorityFingerprint}'
      : null;
  bool get canEdit =>
      _currentLease != null && profile != null && !busy && !mustReload;
  Future<void> load() => _run(null);
  Future<void> save(BuyerProfileEdit edit) =>
      canEdit ? _run(edit) : Future.value();
  Future<void> _run(BuyerProfileEdit? edit) async {
    if (busy || _currentLease == null) return;
    final generation = ++_generation;
    final lease = _lease;
    busy = true;
    message = null;
    notifyListeners();
    bool current() => generation == _generation && lease == _currentLease;
    try {
      final result = edit == null
          ? await _repository.load()
          : await _repository.update(profile!, edit);
      if (!current()) return;
      if (result.userId != _access.snapshot.currentContext?.userId) {
        throw const NexaApiFailure(
          code: 'profile_identity_mismatch',
          userMessage: 'Actualiza tu acceso.',
        );
      }
      profile = result;
      mustReload = false;
      messageIsError = false;
      if (edit != null) message = 'Tus datos se guardaron.';
    } catch (error) {
      if (!current()) return;
      if (error is NexaApiFailure && error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      messageIsError = true;
      final status = error is NexaApiFailure ? error.statusCode : null;
      mustReload = edit != null && !{400, 403, 422}.contains(status);
      message = switch (status) {
        409 ||
        412 => 'Tus datos cambiaron. Actualiza el perfil antes de editar.',
        400 ||
        422 => 'Revisa tus datos. El teléfono debe incluir el código de país.',
        403 => 'Tu acceso actual no permite esta acción.',
        _ =>
          edit == null ? 'No pudimos cargar tus datos. Vuelve a intentarlo.' : 'No pudimos confirmar el cambio. Actualiza el perfil antes de volver a guardar.',
      };
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
