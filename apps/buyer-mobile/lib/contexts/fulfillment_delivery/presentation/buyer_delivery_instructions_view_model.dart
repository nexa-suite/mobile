import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_delivery_instructions_repository.dart';

final class BuyerDeliveryInstructionsViewModel extends ChangeNotifier {
  BuyerDeliveryInstructionsViewModel(
    this._repository,
    this._access,
    this.orderId,
  ) {
    _lease = scope;
    _subscription = _access.changes.listen((_) {
      if (_lease == scope) return;
      _lease = scope;
      _generation++;
      value = null;
      _pending = null;
      busy = false;
      mustReload = false;
      message = null;
      notifyListeners();
    });
  }
  final BuyerDeliveryInstructionsRepository _repository;
  final BuyerAccessRepository _access;
  final String orderId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  int _generation = 0;
  BuyerDeliveryInstructions? value;
  BuyerDeliveryInstructionCommand? _pending;
  bool busy = false, mustReload = false, messageIsError = false;
  String? message;
  String? get scope =>
      _access.snapshot.hasActiveBuyerScope &&
          _access.snapshot.currentContext!.permissions.contains(
            'buyer.order.read',
          )
      ? '${_access.snapshot.authorityEpoch}|${_access.snapshot.currentContext!.authorityFingerprint}'
      : null;
  bool get _write =>
      scope != null &&
      _access.snapshot.currentContext!.permissions.contains(
        'buyer.sales.write',
      );
  bool get canEdit =>
      _write &&
      value?.editable == true &&
      !busy &&
      !mustReload &&
      _pending == null;
  bool get canRetry => _write && !busy && _pending != null;
  Future<void> load() => _pending == null ? _run(null) : Future.value();
  Future<void> save(
    String kind,
    String content, {
    required int expectedVersion,
    String? instructionId,
  }) async {
    if (!canEdit) return;
    if (value!.version != expectedVersion) {
      messageIsError = true;
      message = 'Las indicaciones cambiaron mientras escribías. Revisa la información actual antes de editar.';
      notifyListeners();
      return;
    }
    final text = content.trim();
    if (text.isEmpty ||
        text.length > 2000 ||
        !instructionKinds.contains(kind)) {
      return;
    }
    _pending = BuyerDeliveryInstructionCommand(
      instructionId: instructionId ?? const Uuid().v4(),
      expectedVersion: value!.version,
      kind: kind,
      content: text,
      idempotencyKey: const Uuid().v4(),
    );
    await _run(_pending);
  }

  Future<void> retry() => canRetry ? _run(_pending) : Future.value();
  Future<void> _run(BuyerDeliveryInstructionCommand? command) async {
    if (busy || scope == null) return;
    final generation = ++_generation, lease = scope;
    bool current() => generation == _generation && lease == scope;
    busy = true;
    message = null;
    notifyListeners();
    try {
      final result = command == null
          ? await _repository.load(orderId)
          : await _repository.publish(orderId, command);
      if (!current()) return;
      value = result;
      _pending = null;
      mustReload = false;
      messageIsError = false;
      if (command != null) {
        message = 'Indicación guardada para el equipo de entrega.';
      }
    } catch (error) {
      if (!current()) return;
      final status = error is NexaApiFailure ? error.statusCode : null;
      if (status == 401) {
        _access.invalidateLocalSession();
        return;
      }
      messageIsError = true;
      if ({400, 403, 404, 409, 412, 422, 428}.contains(status)) {
        _pending = null;
        mustReload = command != null;
      }
      if ({403, 404}.contains(status)) value = null;
      message = switch (status) {
        403 => 'Tu acceso no permite esta acción.',
        404 => 'Este pedido ya no está disponible.',
        409 || 412 || 428 =>
          'La entrega cambió. Actualiza las indicaciones antes de guardar.',
        400 || 422 => 'Revisa la indicación antes de enviarla.',
        _ =>
          command == null
              ? 'No pudimos cargar las indicaciones. Vuelve a consultar.'
              : 'No pudimos confirmar el cambio. Reintenta la misma indicación.',
      };
    } finally {
      if (current()) {
        busy = false;
        notifyListeners();
      }
    }
  }

  static const instructionKinds = {
    'NORMAL',
    'COLD_CHAIN',
    'ACCESS_RESTRICTION',
    'SPECIAL_UNLOADING',
    'CUSTOMER_SAFETY',
    'GOODS_HANDLING',
  };
  @override
  void dispose() {
    _generation++;
    _pending = null;
    _subscription.cancel();
    super.dispose();
  }
}
