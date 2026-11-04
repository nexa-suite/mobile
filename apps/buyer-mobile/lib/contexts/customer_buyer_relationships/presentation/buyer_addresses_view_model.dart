import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_addresses_repository.dart';

final class BuyerAddressesViewModel extends ChangeNotifier {
  BuyerAddressesViewModel(this._repository, this._access) {
    _lease = scope;
    _subscription = _access.changes.listen((_) {
      if (_lease == scope) return;
      _lease = scope;
      _generation++;
      items = const [];
      busy = false;
      mustReload = false;
      loaded = false;
      message = null;
      notifyListeners();
    });
  }
  final BuyerAddressesRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  int _generation = 0;
  List<BuyerAddress> items = const [];
  bool busy = false, loaded = false, mustReload = false, messageIsError = false;
  String? message;
  String? get scope =>
      _access.snapshot.hasActiveBuyerScope &&
          _access.snapshot.currentContext!.permissions.contains(
            'buyer.sales.read',
          )
      ? '${_access.snapshot.authorityEpoch}|${_access.snapshot.currentContext!.authorityFingerprint}'
      : null;
  bool get canEdit =>
      scope != null &&
      loaded &&
      !busy &&
      !mustReload &&
      _access.snapshot.currentContext!.permissions.contains(
        'buyer.sales.write',
      );
  Future<List<BuyerAddressOption>> options(
    String resource, {
    String? parent,
  }) async {
    final lease = scope;
    if (lease == null) return const [];
    final result = await _repository.options(resource, parent: parent);
    return lease == scope ? result : const [];
  }

  Future<bool> load() => _run(null);
  Future<bool> save(BuyerAddressEdit edit, {BuyerAddress? current}) => canEdit
      ? _run(() async {
          await _repository.save(edit, current: current);
        })
      : Future.value(false);
  Future<bool> makeDefault(BuyerAddress address) => canEdit
      ? _run(() => _repository.makeDefault(address))
      : Future.value(false);
  Future<bool> remove(BuyerAddress address) =>
      canEdit ? _run(() => _repository.remove(address)) : Future.value(false);
  Future<bool> _run(Future<void> Function()? action) async {
    if (busy || scope == null) return false;
    final generation = ++_generation, lease = scope;
    bool current() => generation == _generation && lease == scope;
    busy = true;
    message = null;
    notifyListeners();
    try {
      if (action != null) await action();
      if (!current()) return false;
      final result = await _repository.list();
      if (!current()) return false;
      items = result;
      final wasUncertain = mustReload;
      loaded = true;
      mustReload = false;
      messageIsError = false;
      message = action != null
          ? 'Tus direcciones se actualizaron.'
          : wasUncertain
          ? 'Revisa la lista actualizada antes de volver a realizar el cambio.'
          : null;
      return true;
    } catch (error) {
      if (!current()) return false;
      if (error is NexaApiFailure && error.statusCode == 401) {
        _access.invalidateLocalSession();
        return false;
      }
      final status = error is NexaApiFailure ? error.statusCode : null;
      messageIsError = true;
      if (action != null) mustReload = !{400, 403, 422}.contains(status);
      message = switch (status) {
        400 || 422 => 'Revisa los datos de la dirección y vuelve a guardar.',
        403 => 'Tu acceso actual no permite esta acción.',
        404 =>
          'Las direcciones personales no están disponibles en este momento.',
        409 ||
        412 ||
        428 => 'La dirección cambió. Actualiza la lista antes de editar.',
        _ =>
          action == null
              ? 'No pudimos cargar tus direcciones. Vuelve a intentarlo.'
              : 'No pudimos confirmar el cambio. Actualiza la lista antes de repetirlo.',
      };
      return false;
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
