import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_wallet_checkout_port.dart';
import '../application/buyer_wallet_recharge_command_store.dart';
import '../application/buyer_wallet_recharge_intent_repository.dart';
import '../application/buyer_wallet_repository.dart';

enum BuyerWalletViewStatus {
  idle,
  loading,
  current,
  notInitialized,
  unavailable,
  permissionDenied,
}

enum BuyerWalletRechargeActionStatus {
  idle,
  preparing,
  submitting,
  pending,
  checking,
  openingCheckout,
  uncertain,
  conflict,
  rejected,
  unavailable,
  permissionDenied,
  storageUnavailable,
}

final class BuyerWalletViewModel extends ChangeNotifier {
  BuyerWalletViewModel(
    this._repository,
    this._access,
    this._rechargeStore, {
    this._rechargeIntents,
    this._checkout,
  }) : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const pageSize = 25;
  static const readPermission = 'payment.read';
  static const createPermission = 'payment.create';
  static const buyerRole = 'BUYER';

  final BuyerWalletRepository _repository;
  final BuyerAccessRepository _access;
  final BuyerWalletRechargeCommandStore _rechargeStore;
  final BuyerWalletRechargeIntentRepository? _rechargeIntents;
  final BuyerWalletCheckoutPort? _checkout;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;
  int _rechargeGeneration = 0;

  BuyerWalletViewStatus status = BuyerWalletViewStatus.idle;
  BuyerWalletProjection? wallet;
  String? message;
  BuyerWalletRechargeActionStatus rechargeActionStatus =
      BuyerWalletRechargeActionStatus.idle;
  BuyerWalletRechargeCommand? rechargeCommand;
  BuyerWalletRechargeProjection? recharge;
  String? rechargeMessage;
  _RechargeCheckoutCredential? _checkoutCredential;

  bool get canRead => _hasReadAuthority(_access.snapshot);
  bool get canCreateRecharge => _hasCreateAuthority(_access.snapshot) &&
      rechargeCommand == null && !_isRechargeBusy;
  bool get canRetryRecharge => _hasCreateAuthority(_access.snapshot) &&
      rechargeCommand != null && rechargeCommand?.rechargeId == null &&
      (rechargeActionStatus == BuyerWalletRechargeActionStatus.uncertain ||
          rechargeActionStatus == BuyerWalletRechargeActionStatus.unavailable ||
          rechargeActionStatus ==
              BuyerWalletRechargeActionStatus.storageUnavailable);
  bool get canRefreshRecharge =>
      _hasReadAuthority(_access.snapshot) &&
      rechargeCommand?.rechargeId != null &&
      !_isRechargeBusy;
  bool get canContinueRechargeCheckout {
    final credential = _checkoutCredential;
    final snapshot = _access.snapshot;
    return credential != null &&
        credential.authorityLeaseKey == _leaseKey &&
        credential.authorityLeaseKey == _activeLease(snapshot) &&
        _matchesCheckoutLease(snapshot, credential.checkoutLease) &&
        _hasCreateAuthority(snapshot) &&
        recharge?.id == credential.checkoutLease.rechargeId &&
        recharge?.providerPaymentIntentId ==
            credential.checkoutLease.providerPaymentIntentId &&
        recharge?.status == BuyerWalletRechargeStatus.awaitingPayment &&
        _checkout?.isConfigured == true &&
        !_isRechargeBusy;
  }
  bool get isRechargeBusy => _isRechargeBusy;

  bool get _isRechargeBusy =>
      rechargeActionStatus == BuyerWalletRechargeActionStatus.preparing ||
      rechargeActionStatus == BuyerWalletRechargeActionStatus.submitting ||
      rechargeActionStatus == BuyerWalletRechargeActionStatus.checking ||
      rechargeActionStatus == BuyerWalletRechargeActionStatus.openingCheckout;

  Future<void> refresh() async {
    await loadPage(0);
    await refreshRechargeStatus();
  }

  Future<void> requestRecharge(String rawAmount) async {
    final lease = _leaseKey;
    final scopeKey = _activeScope(_access.snapshot);
    if (lease == null) {
      _setRechargeFailure(
        BuyerWalletRechargeActionStatus.unavailable,
        'Inicia sesión para solicitar una recarga.',
      );
      return;
    }
    if (scopeKey == null) return;
    if (!_hasCreateAuthority(_access.snapshot)) {
      _setRechargeFailure(
        BuyerWalletRechargeActionStatus.permissionDenied,
        'No tienes permiso para solicitar una recarga de Buyer.',
      );
      return;
    }
    if (_isRechargeBusy) return;
    final amount = _normalizeRechargeAmount(rawAmount);
    if (amount == null) {
      _setRechargeFailure(
        BuyerWalletRechargeActionStatus.rejected,
        'Ingresa un monto entre 0.01 y 999999.99 PEN, con hasta dos decimales.',
      );
      return;
    }

    final generation = ++_rechargeGeneration;
    _checkoutCredential = null;
    rechargeActionStatus = BuyerWalletRechargeActionStatus.preparing;
    rechargeMessage = 'Guardando una solicitud recuperable antes de enviarla…';
    notifyListeners();
    try {
      final command = await _rechargeStore.prepare(
        scopeKey: scopeKey,
        amount: amount,
      );
      if (!_isCurrentRecharge(generation, lease)) return;
      rechargeCommand = command;
      if (command.amount != amount) {
        rechargeActionStatus = command.rechargeId == null
            ? BuyerWalletRechargeActionStatus.uncertain
            : BuyerWalletRechargeActionStatus.pending;
        rechargeMessage = command.rechargeId == null
            ? 'Hay una solicitud anterior sin resolver por ${command.amount} PEN. Reintenta ese mismo monto antes de iniciar otra.'
            : 'Hay una recarga anterior pendiente. Consulta su estado antes de iniciar otra.';
        notifyListeners();
        if (command.rechargeId != null) await refreshRechargeStatus();
        return;
      }
      if (command.rechargeId != null) {
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage = 'Esta solicitud ya existe. Consulta su estado vigente.';
        notifyListeners();
        await refreshRechargeStatus();
        return;
      }
      await _submitRecharge(command, generation, lease);
    } catch (_) {
      if (!_isCurrentRecharge(generation, lease)) return;
      rechargeActionStatus = BuyerWalletRechargeActionStatus.storageUnavailable;
      rechargeMessage =
          'No se pudo guardar de forma segura la solicitud de recarga. No se envió.';
      notifyListeners();
    }
  }

  Future<void> retryRechargeCreation() async {
    final lease = _leaseKey;
    final scopeKey = _activeScope(_access.snapshot);
    if (lease == null || !_hasCreateAuthority(_access.snapshot)) {
      _setRechargeFailure(
        BuyerWalletRechargeActionStatus.permissionDenied,
        'No tienes permiso vigente para solicitar una recarga.',
      );
      return;
    }
    if (scopeKey == null) return;
    if (_isRechargeBusy) return;
    final generation = ++_rechargeGeneration;
    try {
      final command = rechargeCommand ??
          await _rechargeStore.load(scopeKey: scopeKey);
      if (!_isCurrentRecharge(generation, lease)) return;
      if (command == null) {
        rechargeActionStatus = BuyerWalletRechargeActionStatus.idle;
        rechargeMessage = null;
        notifyListeners();
        return;
      }
      rechargeCommand = command;
      if (command.rechargeId != null) {
        await refreshRechargeStatus();
        return;
      }
      await _submitRecharge(command, generation, lease);
    } catch (_) {
      if (!_isCurrentRecharge(generation, lease)) return;
      rechargeActionStatus = BuyerWalletRechargeActionStatus.storageUnavailable;
      rechargeMessage = 'No se pudo leer la solicitud guardada de forma segura.';
      notifyListeners();
    }
  }

  Future<void> refreshRechargeStatus() async {
    final lease = _leaseKey;
    final scopeKey = _activeScope(_access.snapshot);
    if (lease == null || scopeKey == null) return;
    final generation = ++_rechargeGeneration;
    try {
      final stored = await _rechargeStore.load(scopeKey: scopeKey);
      if (!_isCurrentRecharge(generation, lease)) return;
      final inMemory = rechargeCommand;
      final command = inMemory != null &&
              stored?.idempotencyKey == inMemory.idempotencyKey &&
              inMemory.rechargeId != null
          ? inMemory
          : stored;
      if (command == null) return;
      rechargeCommand = command;
      final rechargeId = command.rechargeId;
      if (rechargeId == null) {
        recharge = null;
        rechargeActionStatus = BuyerWalletRechargeActionStatus.uncertain;
        rechargeMessage =
            'No se conoce el resultado de la solicitud. Reintenta solo el mismo monto y la misma identidad.';
        notifyListeners();
        return;
      }
      if (!_hasReadAuthority(_access.snapshot)) {
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage =
            'La solicitud sigue registrada, pero falta payment.read para consultar su estado.';
        notifyListeners();
        return;
      }
      rechargeActionStatus = BuyerWalletRechargeActionStatus.checking;
      rechargeMessage = 'Consultando el estado vigente con Nexa…';
      notifyListeners();
      final current = await _repository.readRecharge(rechargeId: rechargeId);
      if (!_isCurrentRecharge(generation, lease)) return;
      recharge = current;
      if (current.isTerminal) {
        _clearCheckoutCredential(rechargeId: current.id);
        await _completeRechargeCommand(
          command,
          current,
          generation,
          lease,
          scopeKey: scopeKey,
        );
      } else {
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage = _pendingRechargeMessage(
          current.status,
          providerPaymentIntentId: current.providerPaymentIntentId,
        );
        notifyListeners();
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentRecharge(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      rechargeActionStatus = failure.statusCode == 403
          ? BuyerWalletRechargeActionStatus.permissionDenied
          : BuyerWalletRechargeActionStatus.unavailable;
      rechargeMessage = failure.statusCode == 403
          ? 'No tienes permiso para consultar el estado de esta recarga.'
          : failure.code == 'BUYER_WALLET_STORE_UNAVAILABLE'
          ? 'El registro de billetera de Nexa no está disponible. Vuelve a consultar el estado.'
          : failure.userMessage;
      notifyListeners();
    } catch (_) {
      if (!_isCurrentRecharge(generation, lease)) return;
      rechargeActionStatus = BuyerWalletRechargeActionStatus.unavailable;
      rechargeMessage = 'No se pudo consultar el estado vigente de la recarga.';
      notifyListeners();
    }
  }

  Future<void> continueRechargeCheckout() async {
    final credential = _checkoutCredential;
    if (credential == null || _isRechargeBusy) return;
    if (!_isCurrentCheckoutCredential(
      _rechargeGeneration,
      credential,
      credential.checkoutLease,
    )) {
      return;
    }
    if (!canContinueRechargeCheckout) return;
    final generation = ++_rechargeGeneration;
    rechargeActionStatus = BuyerWalletRechargeActionStatus.openingCheckout;
    rechargeMessage = 'Abriendo el checkout seguro de Stripe…';
    notifyListeners();
    if (!_isCurrentCheckoutCredential(
      generation,
      credential,
      credential.checkoutLease,
    )) {
      return;
    }

    BuyerWalletCheckoutOutcome outcome;
    try {
      outcome = await _checkout!.present(
        clientSecret: credential.clientSecret,
        lease: credential.checkoutLease,
        isLeaseCurrent: (candidate) => _isCurrentCheckoutCredential(
          generation,
          credential,
          candidate,
        ),
      );
    } catch (_) {
      outcome = BuyerWalletCheckoutOutcome.failed;
    }
    if (!_isCurrentCheckoutCredential(
      generation,
      credential,
      credential.checkoutLease,
    )) {
      return;
    }

    switch (outcome) {
      case BuyerWalletCheckoutOutcome.completed:
        _clearCheckoutCredential(rechargeId: credential.rechargeId);
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage =
            'Stripe terminó el checkout. Nexa todavía debe verificar su callback firmado antes de acreditar el saldo.';
      case BuyerWalletCheckoutOutcome.cancelled:
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage =
            'Checkout cerrado. La recarga sigue pendiente; puedes continuar el mismo intento sin crear otra solicitud.';
      case BuyerWalletCheckoutOutcome.failed:
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage =
            'El checkout no se completó. La recarga sigue pendiente; puedes reintentar el mismo intento.';
      case BuyerWalletCheckoutOutcome.unavailable:
        _clearCheckoutCredential(rechargeId: credential.rechargeId);
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage =
            'El checkout no está disponible en esta plataforma. La recarga sigue pendiente y no se creó otro intento.';
      case BuyerWalletCheckoutOutcome.leaseInvalidated:
        _clearCheckoutCredential(rechargeId: credential.rechargeId);
        rechargeActionStatus = BuyerWalletRechargeActionStatus.pending;
        rechargeMessage =
            'El checkout perdió su acceso vigente. La recarga sigue pendiente; consulta su estado antes de continuar.';
        notifyListeners();
        return;
    }
    notifyListeners();
    await refreshRechargeStatus();
  }

  Future<void> _submitRecharge(
    BuyerWalletRechargeCommand command,
    int generation,
    String lease,
  ) async {
    final scopeKey = _activeScope(_access.snapshot);
    if (scopeKey == null) return;
    rechargeActionStatus = BuyerWalletRechargeActionStatus.submitting;
    rechargeMessage = 'Enviando la solicitud de recarga a Nexa…';
    notifyListeners();
    try {
      final intent = _rechargeIntents == null
          ? null
          : await _rechargeIntents.createRechargeIntent(
              amount: command.amount,
              idempotencyKey: command.idempotencyKey,
            );
      if (!_isCurrentRecharge(generation, lease)) return;
      final created = intent?.recharge ??
          await _repository.createRecharge(
            amount: command.amount,
            idempotencyKey: command.idempotencyKey,
          );
      if (!_isCurrentRecharge(generation, lease)) return;
      final recoveredCommand = command.withRechargeId(created.id);
      var persisted = false;
      try {
        persisted = await _rechargeStore.recordCreated(
          scopeKey: scopeKey,
          idempotencyKey: command.idempotencyKey,
          rechargeId: created.id,
        );
      } catch (_) {
        // The in-memory server identity still permits a self-scoped GET; retry uses the same key.
      }
      if (!_isCurrentRecharge(generation, lease)) return;
      rechargeCommand = recoveredCommand;
      recharge = created;
      if (created.isTerminal) {
        _clearCheckoutCredential(rechargeId: created.id);
        await _completeRechargeCommand(
          recoveredCommand,
          created,
          generation,
          lease,
          scopeKey: scopeKey,
          persistenceConfirmed: persisted,
        );
        return;
      }
      rechargeActionStatus = persisted
          ? BuyerWalletRechargeActionStatus.pending
          : BuyerWalletRechargeActionStatus.storageUnavailable;
      rechargeMessage = persisted
          ? _checkoutPendingMessage(created)
          : 'Nexa creó la solicitud, pero no se guardó su referencia de recuperación. Consulta el estado y conserva esta pantalla abierta.';
      final checkoutLease = _checkoutLeaseFor(_access.snapshot, created);
      if (_canOpenCheckout(created, intent?.clientSecret) &&
          checkoutLease != null) {
        _checkoutCredential = _RechargeCheckoutCredential(
          authorityLeaseKey: lease,
          checkoutLease: checkoutLease,
          clientSecret: intent!.clientSecret!,
        );
      } else {
        _clearCheckoutCredential(rechargeId: created.id);
      }
      notifyListeners();
      if (canContinueRechargeCheckout) {
        await continueRechargeCheckout();
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentRecharge(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 400) {
        try {
          final cleared = await _rechargeStore.clear(
            scopeKey: scopeKey,
            idempotencyKey: command.idempotencyKey,
          );
          if (!_isCurrentRecharge(generation, lease)) return;
          if (cleared) rechargeCommand = null;
        } catch (_) {
          // Keep the durable command if its deterministic rejection cannot be cleared.
        }
      }
      rechargeActionStatus = switch (failure.statusCode) {
        403 => BuyerWalletRechargeActionStatus.permissionDenied,
        409 when failure.code == 'IDEMPOTENCY_PAYLOAD_CONFLICT' =>
          BuyerWalletRechargeActionStatus.conflict,
        400 => BuyerWalletRechargeActionStatus.rejected,
        503 => BuyerWalletRechargeActionStatus.unavailable,
        _ when failure.retryable || failure.statusCode == null =>
          BuyerWalletRechargeActionStatus.uncertain,
        _ => BuyerWalletRechargeActionStatus.unavailable,
      };
      rechargeMessage = switch (rechargeActionStatus) {
        BuyerWalletRechargeActionStatus.permissionDenied =>
          'No tienes permiso para solicitar una recarga de Buyer.',
        BuyerWalletRechargeActionStatus.conflict =>
          'La clave guardada ya corresponde a otro monto. No se creó una nueva solicitud; conserva el caso para revisión.',
        BuyerWalletRechargeActionStatus.rejected => failure.userMessage,
        BuyerWalletRechargeActionStatus.uncertain =>
          'Nexa no confirmó el resultado. Reintenta solo esta misma solicitud para recuperar su estado.',
        BuyerWalletRechargeActionStatus.unavailable
            when failure.code == 'BUYER_WALLET_STORE_UNAVAILABLE' =>
          'El registro de billetera de Nexa no está disponible. No se confirmó ningún abono; vuelve a intentar la misma solicitud.',
        _ => failure.userMessage,
      };
      notifyListeners();
    } catch (_) {
      if (!_isCurrentRecharge(generation, lease)) return;
      rechargeActionStatus = BuyerWalletRechargeActionStatus.uncertain;
      rechargeMessage =
          'No se pudo confirmar la respuesta. Reintenta solo esta misma solicitud.';
      notifyListeners();
    }
  }

  Future<void> _completeRechargeCommand(
    BuyerWalletRechargeCommand command,
    BuyerWalletRechargeProjection current,
    int generation,
    String lease, {
    required String scopeKey,
    bool persistenceConfirmed = true,
  }) async {
    _clearCheckoutCredential(rechargeId: current.id);
    var cleared = false;
    if (persistenceConfirmed) {
      try {
        cleared = await _rechargeStore.clear(
          scopeKey: scopeKey,
          idempotencyKey: command.idempotencyKey,
        );
      } catch (_) {
        cleared = false;
      }
    }
    if (!_isCurrentRecharge(generation, lease)) return;
    if (cleared) rechargeCommand = null;
    rechargeActionStatus = cleared
        ? BuyerWalletRechargeActionStatus.idle
        : BuyerWalletRechargeActionStatus.storageUnavailable;
    rechargeMessage = cleared
        ? _terminalRechargeMessage(current.status)
        : 'Nexa registró un estado terminal, pero no se pudo limpiar la recuperación local. Actualiza antes de iniciar otra recarga.';
    notifyListeners();
    if (current.status == BuyerWalletRechargeStatus.succeeded && canRead) {
      await loadPage(wallet?.movements.page ?? 0);
    }
  }

  bool _canOpenCheckout(
    BuyerWalletRechargeProjection intent,
    String? clientSecret,
  ) =>
      _checkout?.isConfigured == true &&
      intent.status == BuyerWalletRechargeStatus.awaitingPayment &&
      intent.provider.toUpperCase() == 'STRIPE' &&
      !_isLocalMockProviderIntent(intent.providerPaymentIntentId) &&
      clientSecret != null &&
      clientSecret.startsWith('${intent.providerPaymentIntentId}_secret_') &&
      clientSecret.length > '${intent.providerPaymentIntentId}_secret_'.length;

  BuyerWalletCheckoutLease? _checkoutLeaseFor(
    BuyerAccessSnapshot snapshot,
    BuyerWalletRechargeProjection intent,
  ) {
    if (!_hasCreateAuthority(snapshot)) return null;
    final context = snapshot.currentContext!;
    final userId = context.userId;
    if (userId == null ||
        userId.isEmpty ||
        context.tenantId.isEmpty ||
        context.workspaceId.isEmpty ||
        context.membershipId.isEmpty ||
        intent.id.isEmpty ||
        intent.providerPaymentIntentId.isEmpty) {
      return null;
    }
    return BuyerWalletCheckoutLease(
      authorityEpoch: snapshot.authorityEpoch,
      userId: userId,
      tenantId: context.tenantId,
      workspaceId: context.workspaceId,
      membershipId: context.membershipId,
      authorizationVersion: context.authorizationVersion,
      authorityFingerprint: context.authorityFingerprint,
      rechargeId: intent.id,
      providerPaymentIntentId: intent.providerPaymentIntentId,
    );
  }

  bool _matchesCheckoutLease(
    BuyerAccessSnapshot snapshot,
    BuyerWalletCheckoutLease lease,
  ) {
    if (!_hasCreateAuthority(snapshot)) return false;
    final context = snapshot.currentContext!;
    return snapshot.authorityEpoch == lease.authorityEpoch &&
        context.userId == lease.userId &&
        context.tenantId == lease.tenantId &&
        context.workspaceId == lease.workspaceId &&
        context.membershipId == lease.membershipId &&
        context.authorizationVersion == lease.authorizationVersion &&
        context.authorityFingerprint == lease.authorityFingerprint;
  }

  bool _isCurrentCheckoutCredential(
    int generation,
    _RechargeCheckoutCredential credential,
    BuyerWalletCheckoutLease candidate,
  ) {
    final generationCurrent = _isCurrentRecharge(
      generation,
      credential.authorityLeaseKey,
    );
    final snapshot = _access.snapshot;
    final currentRecharge = recharge;
    final isCurrent = generationCurrent &&
        identical(_checkoutCredential, credential) &&
        candidate == credential.checkoutLease &&
        _matchesCheckoutLease(snapshot, candidate) &&
        currentRecharge?.id == candidate.rechargeId &&
        currentRecharge?.providerPaymentIntentId ==
            candidate.providerPaymentIntentId &&
        currentRecharge?.provider.toUpperCase() == 'STRIPE' &&
        currentRecharge?.status == BuyerWalletRechargeStatus.awaitingPayment;
    if (!isCurrent) {
      _clearCheckoutCredential(rechargeId: credential.rechargeId);
    }
    return isCurrent;
  }

  String _checkoutPendingMessage(BuyerWalletRechargeProjection intent) {
    if (intent.status != BuyerWalletRechargeStatus.awaitingPayment) {
      return _pendingRechargeMessage(
        intent.status,
        providerPaymentIntentId: intent.providerPaymentIntentId,
      );
    }
    if (_isLocalMockProviderIntent(intent.providerPaymentIntentId)) {
      return 'PSP local de demostración: PaymentSheet no se abre para este intento. '
          'Sigue pendiente hasta que Nexa procese un callback firmado; no se declara pago exitoso en el dispositivo.';
    }
    if (intent.provider.toUpperCase() != 'STRIPE') {
      return 'La recarga sigue pendiente. No hay checkout móvil configurado para el proveedor recibido; no se creó otro intento.';
    }
    if (_checkout?.isConfigured != true) {
      return 'La recarga sigue pendiente. Falta una publishable key Stripe válida para abrir el checkout; no se creó otro intento.';
    }
    return 'La recarga sigue pendiente. El checkout no se reabrió porque esta respuesta no trajo un secreto válido; consulta su estado y no crees otra solicitud.';
  }

  bool _isLocalMockProviderIntent(String providerPaymentIntentId) =>
      providerPaymentIntentId.startsWith('pi_local_');

  String _pendingRechargeMessage(
    BuyerWalletRechargeStatus status, {
    String? providerPaymentIntentId,
  }) => switch (status) {
    BuyerWalletRechargeStatus.preparing =>
      'Nexa está preparando la solicitud. Todavía no hay fondos abonados.',
    BuyerWalletRechargeStatus.awaitingPayment
        when providerPaymentIntentId != null &&
            _isLocalMockProviderIntent(providerPaymentIntentId) =>
      'PSP local de demostración: pago pendiente. Nexa acredita el saldo solo al procesar su callback firmado.',
    BuyerWalletRechargeStatus.awaitingPayment =>
      'Pago pendiente. Nexa acredita el saldo solo al procesar su callback firmado.',
    BuyerWalletRechargeStatus.succeeded =>
      'Nexa confirmó la recarga. Actualizando el saldo y los movimientos vigentes.',
    BuyerWalletRechargeStatus.failed => 'El proveedor rechazó o no completó la recarga.',
    BuyerWalletRechargeStatus.cancelled => 'La solicitud de recarga fue cancelada.',
    BuyerWalletRechargeStatus.rejected => 'Nexa rechazó la solicitud de recarga.',
  };

  String _terminalRechargeMessage(BuyerWalletRechargeStatus status) => switch (status) {
    BuyerWalletRechargeStatus.succeeded =>
      'Nexa confirmó la recarga y actualizó el estado de la billetera.',
    BuyerWalletRechargeStatus.failed => 'La recarga terminó sin abonar fondos.',
    BuyerWalletRechargeStatus.cancelled => 'La recarga fue cancelada sin abonar fondos.',
    BuyerWalletRechargeStatus.rejected => 'La recarga fue rechazada sin abonar fondos.',
    BuyerWalletRechargeStatus.preparing ||
    BuyerWalletRechargeStatus.awaitingPayment => _pendingRechargeMessage(status),
  };

  String? _normalizeRechargeAmount(String raw) {
    final value = raw.trim();
    if (!RegExp(r'^\d{1,6}(?:\.\d{1,2})?$').hasMatch(value)) return null;
    final parts = value.split('.');
    final whole = int.tryParse(parts[0]);
    final cents = parts.length == 1 ? 0 : int.tryParse(parts[1].padRight(2, '0'));
    if (whole == null || cents == null) return null;
    final minor = whole * 100 + cents;
    if (minor < 1 || minor > 99999999) return null;
    return '$whole.${cents.toString().padLeft(2, '0')}';
  }

  bool _isCurrentRecharge(int generation, String lease) =>
      _isCurrentGeneration(generation, lease, _rechargeGeneration);

  void _clearCheckoutCredential({String? rechargeId}) {
    final current = _checkoutCredential;
    if (current != null &&
        (rechargeId == null || current.rechargeId == rechargeId)) {
      _checkoutCredential = null;
    }
  }

  void _setRechargeFailure(
    BuyerWalletRechargeActionStatus status,
    String message,
  ) {
    rechargeActionStatus = status;
    rechargeMessage = message;
    notifyListeners();
  }

  Future<void> nextPage() {
    final current = wallet?.movements;
    if (status != BuyerWalletViewStatus.current ||
        current == null ||
        (current.page + 1) * current.size >= current.total) {
      return Future.value();
    }
    return loadPage(current.page + 1);
  }

  Future<void> previousPage() {
    final current = wallet?.movements;
    if (status != BuyerWalletViewStatus.current ||
        current == null ||
        current.page == 0) {
      return Future.value();
    }
    return loadPage(current.page - 1);
  }

  Future<void> loadPage(int page) async {
    final lease = _leaseKey;
    if (lease == null) {
      _clear(
        BuyerWalletViewStatus.unavailable,
        'Inicia sesión para consultar tu billetera.',
      );
      return;
    }
    if (!canRead) {
      _requestGeneration++;
      _clear(
        BuyerWalletViewStatus.permissionDenied,
        'No tienes permiso para consultar la billetera de Buyer.',
      );
      return;
    }
    if (page < 0) {
      _clear(
        BuyerWalletViewStatus.unavailable,
        'No se pudo abrir esta página de movimientos.',
      );
      return;
    }

    final generation = ++_requestGeneration;
    status = BuyerWalletViewStatus.loading;
    wallet = null;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.readCurrentWallet(page: page);
      if (!_isCurrent(generation, lease)) return;
      wallet = result;
      status = result.state == BuyerWalletState.active
          ? BuyerWalletViewStatus.current
          : BuyerWalletViewStatus.notInitialized;
      message = null;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        _clear(
          BuyerWalletViewStatus.permissionDenied,
          'No tienes permiso para consultar la billetera de Buyer.',
        );
        return;
      }
      _clear(
        BuyerWalletViewStatus.unavailable,
        failure.statusCode == 503
            ? 'La billetera no está disponible en este momento.'
            : failure.userMessage,
      );
      return;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      _clear(
        BuyerWalletViewStatus.unavailable,
        'No se pudo consultar la billetera.',
      );
      return;
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      _isCurrentGeneration(generation, lease, _requestGeneration);

  bool _isCurrentGeneration(
    int generation,
    String lease,
    int activeGeneration,
  ) {
    if (generation != activeGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != _activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = _activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    _rechargeGeneration++;
    _clearCheckoutCredential();
    rechargeCommand = null;
    recharge = null;
    rechargeActionStatus = BuyerWalletRechargeActionStatus.idle;
    rechargeMessage = null;
    _clear(
      snapshot.isSignedIn && !_hasReadAuthority(snapshot)
          ? BuyerWalletViewStatus.permissionDenied
          : BuyerWalletViewStatus.idle,
      snapshot.isSignedIn && !_hasReadAuthority(snapshot)
          ? 'No tienes permiso para consultar la billetera de Buyer.'
          : null,
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _rechargeGeneration++;
    _clearCheckoutCredential();
    rechargeCommand = null;
    recharge = null;
    rechargeActionStatus = BuyerWalletRechargeActionStatus.permissionDenied;
    rechargeMessage = 'El acceso activo cambió. Inicia sesión nuevamente.';
    _clear(
      BuyerWalletViewStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clear(BuyerWalletViewStatus status, String? message) {
    this.status = status;
    wallet = null;
    this.message = message;
    notifyListeners();
  }

  static bool _hasReadAuthority(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.roles.contains(buyerRole) &&
      snapshot.currentContext!.permissions.contains(readPermission);

  static bool _hasCreateAuthority(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.roles.contains(buyerRole) &&
      snapshot.currentContext!.permissions.contains(createPermission);

  static String? _activeScope(BuyerAccessSnapshot snapshot) {
    if (!snapshot.isSignedIn) return null;
    final context = snapshot.currentContext!;
    final userId = context.userId;
    if (userId == null || userId.isEmpty) return null;
    return '$userId|${context.scopeKey}';
  }

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  static bool _isAuthorityFailure(NexaApiFailure failure) =>
      failure.statusCode == 401 || failure.code == 'ACCESS_CONTEXT_INVALID';

  @override
  void dispose() {
    _requestGeneration++;
    _rechargeGeneration++;
    _clearCheckoutCredential();
    _subscription.cancel();
    super.dispose();
  }
}

final class _RechargeCheckoutCredential {
  const _RechargeCheckoutCredential({
    required this.authorityLeaseKey,
    required this.checkoutLease,
    required this.clientSecret,
  });

  String get rechargeId => checkoutLease.rechargeId;

  final String authorityLeaseKey;
  final BuyerWalletCheckoutLease checkoutLease;
  final String clientSecret;
}
