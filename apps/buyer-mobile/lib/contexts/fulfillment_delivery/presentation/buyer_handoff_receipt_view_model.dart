import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_handoff_receipt_repository.dart';

final class BuyerHandoffReceiptViewModel extends ChangeNotifier {
  BuyerHandoffReceiptViewModel(
    this._repository,
    this._access,
    this.deliveryId,
  ) {
    _lease = _currentLease;
    _subscription = _access.changes.listen((_) {
      if (_lease == _currentLease) return;
      _lease = _currentLease;
      _generation++;
      _pendingToken = null;
      busy = false;
      result = null;
      message = null;
      notifyListeners();
    });
  }
  final BuyerHandoffReceiptRepository _repository;
  final BuyerAccessRepository _access;
  final String deliveryId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  int _generation = 0;
  String? _pendingToken;
  bool busy = false;
  BuyerHandoffReceipt? result;
  String? message;

  String? get _currentLease => _access.snapshot.hasActiveBuyerScope
      ? '${_access.snapshot.authorityEpoch}|${_access.snapshot.currentContext!.authorityFingerprint}'
      : null;
  String? get authorityKey => _currentLease;
  bool get permitted =>
      _currentLease != null &&
      _access.snapshot.currentContext!.permissions.contains('buyer.order.read');
  bool get canRetry => permitted && !busy && _pendingToken != null;
  bool get blocksOtherReceipt =>
      busy || _pendingToken != null || result != null;
  bool get canScan =>
      permitted && !busy && _pendingToken == null && result == null;

  Future<void> accept(String rawToken) async {
    if (!canScan && !canRetry) return;
    final token = rawToken.trim();
    if (token.isEmpty || token.length > 400 || token.contains(RegExp(r'\s'))) {
      message =
          'Este código no corresponde a una entrega. Usa el QR del repartidor.';
      notifyListeners();
      return;
    }
    if (_pendingToken != null && _pendingToken != token) return;
    final lease = _lease;
    final generation = ++_generation;
    // Stable across restarts without persisting the raw one-time token.
    final digest = sha256.convert(utf8.encode(token));
    final key = const Uuid().v5(
      Namespace.url.value,
      'nexa-buyer-accept-v1|${_access.snapshot.currentContext!.scopeKey}|$deliveryId|$digest',
    );
    _pendingToken = token;
    busy = true;
    message = null;
    notifyListeners();
    try {
      final response = await _repository.accept(
        token: token,
        expectedDeliveryId: deliveryId,
        idempotencyKey: key,
      );
      if (generation != _generation || lease != _currentLease) return;
      result = response;
      _pendingToken = null;
      message = response.orderCompleted
          ? 'Recepción confirmada. Tu pedido está completo.'
          : 'Recepción registrada. El pedido aún tiene pasos pendientes.';
    } on NexaApiFailure catch (error) {
      if (generation != _generation || lease != _currentLease) return;
      if (error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      final terminal = {
        400,
        403,
        404,
        409,
        410,
        422,
      }.contains(error.statusCode);
      if (terminal) _pendingToken = null;
      message = switch (error.statusCode) {
        403 => 'Tu acceso no permite recibir esta entrega.',
        404 => 'No encontramos esta entrega o ya no está disponible.',
        409 || 410 => 'La entrega cambió o el código venció. Pide al repartidor que lo revise.',
        400 ||
        422 => 'No pudimos aceptar este código. Pide el QR de esta entrega.',
        _ =>
          'No pudimos confirmar la recepción. Reintenta con el mismo código.',
      };
    } catch (_) {
      if (generation != _generation || lease != _currentLease) return;
      message =
          'No pudimos confirmar la recepción. Reintenta con el mismo código.';
    } finally {
      if (generation == _generation && lease == _currentLease) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> retry() async {
    final token = _pendingToken;
    if (canRetry && token != null) await accept(token);
  }

  @override
  void dispose() {
    _generation++;
    _pendingToken = null;
    _subscription.cancel();
    super.dispose();
  }
}
