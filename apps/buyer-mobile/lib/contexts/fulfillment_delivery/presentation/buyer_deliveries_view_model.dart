import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_deliveries_repository.dart';
import '../application/buyer_receipt_evidence_idempotency_store.dart';
import '../application/buyer_receipt_idempotency_store.dart';

enum BuyerHandoffStatus {
  idle,
  validating,
  verified,
  invalid,
  unavailable,
  permissionDenied,
}

enum BuyerReceiptStatus {
  idle,
  preparing,
  submitting,
  accepted,
  disputed,
  unavailable,
  conflict,
  permissionDenied,
}

enum BuyerReceiptEvidenceStatus {
  idle,
  preparing,
  uploading,
  uploaded,
  unavailable,
  conflict,
  permissionDenied,
}

enum BuyerLiveLocationStatus {
  idle,
  loading,
  current,
  noSignal,
  stale,
  unavailable,
  permissionDenied,
  inactive,
}

enum BuyerDeliveriesStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BuyerDeliveriesViewModel extends ChangeNotifier {
  BuyerDeliveriesViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const pageSize = 25;
  static const readPermission = 'buyer.tracking.read';

  final BuyerDeliveriesRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;
  int? _activeListRequest;

  BuyerDeliveriesStatus status = BuyerDeliveriesStatus.idle;
  List<BuyerDeliveryProjection> items = const [];
  int page = 0;
  int total = 0;
  String? message;
  bool get isRefreshing =>
      _activeListRequest != null && status == BuyerDeliveriesStatus.current;

  Future<void> refresh() => _load(page);

  Future<void> nextPage() {
    if (status != BuyerDeliveriesStatus.current ||
        (page + 1) * pageSize >= total) {
      return Future.value();
    }
    return _load(page + 1);
  }

  Future<void> previousPage() {
    if (status != BuyerDeliveriesStatus.current || page == 0) {
      return Future.value();
    }
    return _load(page - 1);
  }

  Future<void> _load(int requestedPage) async {
    if (_activeListRequest != null) return;
    final lease = _leaseKey;
    if (lease == null) {
      _clear(
        BuyerDeliveriesStatus.unavailable,
        'Inicia sesión para consultar tus entregas.',
      );
      return;
    }
    if (!BuyerDeliveriesViewModel._hasPermission(_access.snapshot)) {
      _requestGeneration++;
      _clear(
        BuyerDeliveriesStatus.permissionDenied,
        'No tienes permiso para consultar el seguimiento de entregas.',
      );
      return;
    }
    if (requestedPage < 0) {
      _clear(
        BuyerDeliveriesStatus.unavailable,
        'No se pudo abrir esta página de entregas.',
      );
      return;
    }

    final generation = ++_requestGeneration;
    _activeListRequest = generation;
    final keepCurrent =
        status == BuyerDeliveriesStatus.current && requestedPage == page;
    if (keepCurrent) {
      message = null;
    } else {
      status = BuyerDeliveriesStatus.loading;
      items = const [];
      total = 0;
      page = requestedPage;
      message = null;
    }
    notifyListeners();
    try {
      final result = await _repository.list(page: requestedPage);
      if (!_isCurrent(generation, lease)) return;
      items = result.items;
      page = result.page;
      total = result.total;
      status = BuyerDeliveriesStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401 ||
          failure.code == 'ACCESS_CONTEXT_INVALID') {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        _clear(
          BuyerDeliveriesStatus.permissionDenied,
          'No tienes permiso para consultar el seguimiento de entregas.',
        );
      } else if (failure.statusCode == 404) {
        _clear(
          BuyerDeliveriesStatus.unavailable,
          'No se pudieron consultar tus entregas.',
        );
      } else if (keepCurrent) {
        status = BuyerDeliveriesStatus.current;
        message = 'No se pudo actualizar; se muestra la lista recibida.';
      } else {
        status = BuyerDeliveriesStatus.unavailable;
        message = failure.userMessage;
      }
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      if (keepCurrent) {
        status = BuyerDeliveriesStatus.current;
        message = 'No se pudo actualizar; se muestra la lista recibida.';
      } else {
        status = BuyerDeliveriesStatus.unavailable;
        message = 'No se pudieron consultar tus entregas.';
      }
    } finally {
      if (_activeListRequest == generation) _activeListRequest = null;
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) {
    if (generation != _requestGeneration || lease != _leaseKey) return false;
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
    _activeListRequest = null;
    _clear(
      snapshot.isSignedIn && !_hasPermission(snapshot)
          ? BuyerDeliveriesStatus.permissionDenied
          : BuyerDeliveriesStatus.idle,
      snapshot.isSignedIn && !_hasPermission(snapshot)
          ? 'No tienes permiso para consultar el seguimiento de entregas.'
          : null,
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _activeListRequest = null;
    _clear(
      BuyerDeliveriesStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clear(BuyerDeliveriesStatus nextStatus, String? nextMessage) {
    status = nextStatus;
    items = const [];
    page = 0;
    total = 0;
    message = nextMessage;
    notifyListeners();
  }

  static bool _hasPermission(BuyerAccessSnapshot snapshot) =>
      snapshot.hasActiveBuyerScope &&
      snapshot.currentContext!.permissions.contains(readPermission);

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  @override
  void dispose() {
    _requestGeneration++;
    _activeListRequest = null;
    _subscription.cancel();
    super.dispose();
  }
}

enum BuyerDeliveryDetailStatus {
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BuyerDeliveryDetailViewModel extends ChangeNotifier {
  BuyerDeliveryDetailViewModel(
    this._repository,
    this._access,
    this.deliveryId, {
    required BuyerReceiptIdempotencyStore receiptStore,
    BuyerReceiptEvidenceIdempotencyStore? evidenceStore,
  }) : _leaseKey = BuyerDeliveriesViewModel._activeLease(_access.snapshot) {
    _receiptStore = receiptStore;
    _evidenceStore = evidenceStore;
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final BuyerDeliveriesRepository _repository;
  final BuyerAccessRepository _access;
  final String deliveryId;
  late final BuyerReceiptIdempotencyStore _receiptStore;
  late final BuyerReceiptEvidenceIdempotencyStore? _evidenceStore;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;
  int? _activeDetailRequest;
  int _commandGeneration = 0;
  int _evidenceGeneration = 0;
  int _locationGeneration = 0;
  Timer? _locationExpiryTimer;
  int authorityRevision = 0;

  BuyerDeliveryDetailStatus status = BuyerDeliveryDetailStatus.loading;
  BuyerDeliveryProjection? delivery;
  List<BuyerDeliveryEventProjection> events = const [];
  List<BuyerReceiptProjection> receipts = const [];
  String? message;
  BuyerHandoffStatus handoffStatus = BuyerHandoffStatus.idle;
  BuyerHandoffValidationProjection? handoff;
  String? handoffMessage;
  BuyerReceiptStatus receiptStatus = BuyerReceiptStatus.idle;
  BuyerReceiptProjection? receipt;
  String? receiptMessage;
  String? evidenceReceiptId;
  BuyerReceiptEvidenceStatus evidenceStatus = BuyerReceiptEvidenceStatus.idle;
  String? evidenceMessage;
  BuyerLiveLocationStatus locationStatus = BuyerLiveLocationStatus.idle;
  BuyerDeliveryTrackingProjection? location;
  String? locationMessage;
  String? _validatedToken;
  _PendingBuyerReceipt? _pendingReceipt;

  static const receiptPermission = 'buyer.order.read';
  static const evidencePermission = 'buyer.receipt.evidence.attach';

  String? get currentBuyerMembershipId =>
      _access.snapshot.currentContext?.membershipId;

  bool get canValidateHandoff {
    final snapshot = _access.snapshot;
    return _leaseKey != null &&
        snapshot.hasActiveBuyerScope &&
        snapshot.currentContext!.permissions.contains(
          BuyerDeliveriesViewModel.readPermission,
        );
  }

  bool get canSubmitReceipt {
    final snapshot = _access.snapshot;
    return _leaseKey != null &&
        snapshot.hasActiveBuyerScope &&
        snapshot.currentContext!.permissions.contains(receiptPermission) &&
        handoff != null &&
        _validatedToken != null;
  }

  bool canAttachEvidenceTo(BuyerReceiptProjection receipt) {
    final snapshot = _access.snapshot;
    return _evidenceStore != null &&
        status == BuyerDeliveryDetailStatus.current &&
        _leaseKey != null &&
        snapshot.hasActiveBuyerScope &&
        snapshot.currentContext!.permissions.contains(evidencePermission) &&
        receipt.decision == 'DISPUTED' &&
        receipt.deliveryId == deliveryId &&
        (receipts.any((value) => value.id == receipt.id) ||
            this.receipt?.id == receipt.id);
  }

  bool isEvidenceUploadedByCurrentMembership(
    BuyerReceiptEvidenceProjection evidence,
  ) =>
      evidence.requestedByMembershipId != null &&
      evidence.requestedByMembershipId ==
          _access.snapshot.currentContext?.membershipId;

  bool get isUploadingEvidence =>
      evidenceStatus == BuyerReceiptEvidenceStatus.preparing ||
      evidenceStatus == BuyerReceiptEvidenceStatus.uploading;

  bool get canRetryReceipt =>
      receiptStatus == BuyerReceiptStatus.unavailable &&
      _pendingReceipt != null;

  bool get receiptAttemptLocked => _pendingReceipt != null;

  bool get hasActiveDeliveryLocationScope =>
      status == BuyerDeliveryDetailStatus.current &&
      delivery != null &&
      _supportsLiveLocation(delivery!.status);

  BuyerDeliveryTrackingProjection? get currentLocation {
    final value = location;
    if (locationStatus != BuyerLiveLocationStatus.current ||
        value == null ||
        !value.expiresAt.isAfter(DateTime.now().toUtc())) {
      return null;
    }
    return value;
  }

  Future<void> refresh() => load(
    preserveCurrent:
        status == BuyerDeliveryDetailStatus.current && delivery != null,
  );

  Future<void> load({bool preserveCurrent = false}) async {
    if (_activeDetailRequest != null) return;
    final keepCurrent =
        preserveCurrent &&
        status == BuyerDeliveryDetailStatus.current &&
        delivery != null;
    if (!keepCurrent) {
      _evidenceGeneration++;
      _locationGeneration++;
      _locationExpiryTimer?.cancel();
      location = null;
      locationStatus = BuyerLiveLocationStatus.idle;
      locationMessage = null;
    }
    final lease = _leaseKey;
    if (lease == null) {
      _clear(
        BuyerDeliveryDetailStatus.unavailable,
        'Inicia sesión para consultar esta entrega.',
      );
      return;
    }
    if (!BuyerDeliveriesViewModel._hasPermission(_access.snapshot)) {
      _requestGeneration++;
      _clear(
        BuyerDeliveryDetailStatus.permissionDenied,
        'No tienes permiso para consultar el seguimiento de entregas.',
      );
      return;
    }
    final generation = ++_requestGeneration;
    _activeDetailRequest = generation;
    if (!keepCurrent) {
      status = BuyerDeliveryDetailStatus.loading;
      delivery = null;
      events = const [];
      receipts = const [];
      evidenceReceiptId = null;
      evidenceStatus = BuyerReceiptEvidenceStatus.idle;
      evidenceMessage = null;
      message = null;
    } else {
      message = null;
    }
    notifyListeners();
    try {
      final result = await _repository.detail(deliveryId);
      if (!_isCurrent(generation, lease)) return;
      delivery = result.delivery;
      events = result.events;
      receipts = List.unmodifiable(result.receipts);
      status = BuyerDeliveryDetailStatus.current;
      message = null;
      _locationGeneration++;
      if (_supportsLiveLocation(result.delivery.status)) {
        unawaited(refreshLocation());
      } else {
        _clearLocation(
          BuyerLiveLocationStatus.inactive,
          'La ubicación solo está disponible durante una entrega activa.',
        );
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401 ||
          failure.code == 'ACCESS_CONTEXT_INVALID') {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        _clear(
          BuyerDeliveryDetailStatus.permissionDenied,
          'No tienes permiso para consultar el seguimiento de entregas.',
        );
      } else if (failure.statusCode == 404) {
        _clear(
          BuyerDeliveryDetailStatus.unavailable,
          'Esta entrega ya no está disponible.',
        );
      } else if (keepCurrent) {
        status = BuyerDeliveryDetailStatus.current;
        message =
            'No se pudo actualizar; se muestra el último estado recibido.';
      } else {
        status = BuyerDeliveryDetailStatus.unavailable;
        message = failure.userMessage;
      }
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      if (keepCurrent) {
        status = BuyerDeliveryDetailStatus.current;
        message =
            'No se pudo actualizar; se muestra el último estado recibido.';
      } else {
        status = BuyerDeliveryDetailStatus.unavailable;
        message = 'Esta entrega no está disponible.';
      }
    } finally {
      if (_activeDetailRequest == generation) _activeDetailRequest = null;
    }
    notifyListeners();
  }

  Future<void> refreshLocation() async {
    final lease = _leaseKey;
    if (lease == null) {
      _clearLocation(BuyerLiveLocationStatus.inactive, null);
      return;
    }
    if (!BuyerDeliveriesViewModel._hasPermission(_access.snapshot)) {
      _locationGeneration++;
      _clearLocation(
        BuyerLiveLocationStatus.permissionDenied,
        'No tienes permiso para consultar la ubicación de esta entrega.',
      );
      return;
    }
    if (!hasActiveDeliveryLocationScope) {
      _locationGeneration++;
      _clearLocation(
        BuyerLiveLocationStatus.inactive,
        'La ubicación solo está disponible durante una entrega activa.',
      );
      return;
    }
    final generation = ++_locationGeneration;
    final deliveryScope = delivery!.id;
    if (currentLocation == null) {
      locationStatus = BuyerLiveLocationStatus.loading;
      notifyListeners();
    }
    try {
      final result = await _repository.liveLocation(deliveryId);
      if (!_isCurrentLocation(generation, lease, deliveryScope)) return;
      if (result == null) {
        _clearLocation(
          BuyerLiveLocationStatus.noSignal,
          'Sin señal GPS. La ubicación de esta entrega no está disponible por ahora.',
        );
        return;
      }
      if (result.deliveryId != deliveryId ||
          !result.expiresAt.isAfter(DateTime.now().toUtc())) {
        _clearLocation(
          BuyerLiveLocationStatus.stale,
          'La última ubicación está antigua y ya no se muestra.',
        );
        return;
      }
      location = result;
      locationStatus = BuyerLiveLocationStatus.current;
      locationMessage = null;
      _scheduleLocationExpiry(result);
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentLocation(generation, lease, deliveryScope)) return;
      if (failure.statusCode == 401 ||
          failure.code == 'ACCESS_CONTEXT_INVALID') {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        _clearLocation(
          BuyerLiveLocationStatus.permissionDenied,
          'No tienes permiso para consultar la ubicación de esta entrega.',
        );
      } else if (failure.statusCode == 404) {
        _clearLocation(
          BuyerLiveLocationStatus.inactive,
          'No hay una ubicación activa disponible para esta entrega.',
        );
      } else {
        _locationFetchFailed();
      }
    } catch (_) {
      if (!_isCurrentLocation(generation, lease, deliveryScope)) return;
      _locationFetchFailed();
    }
    notifyListeners();
  }

  void _locationFetchFailed() {
    final previous = currentLocation;
    locationStatus = previous == null
        ? BuyerLiveLocationStatus.unavailable
        : BuyerLiveLocationStatus.current;
    locationMessage = previous == null
        ? 'No se pudo actualizar la ubicación.'
        : 'No se pudo actualizar. Se muestra la última ubicación vigente.';
  }

  void _scheduleLocationExpiry(BuyerDeliveryTrackingProjection value) {
    _locationExpiryTimer?.cancel();
    final untilExpiry = value.expiresAt.difference(DateTime.now().toUtc());
    _locationExpiryTimer = Timer(
      untilExpiry.isNegative ? Duration.zero : untilExpiry,
      () {
        if (location?.sampleId != value.sampleId) return;
        _clearLocation(
          BuyerLiveLocationStatus.stale,
          'La última ubicación está antigua y ya no se muestra.',
        );
      },
    );
  }

  void _clearLocation(BuyerLiveLocationStatus nextStatus, String? nextMessage) {
    _locationExpiryTimer?.cancel();
    _locationExpiryTimer = null;
    location = null;
    locationStatus = nextStatus;
    locationMessage = nextMessage;
    notifyListeners();
  }

  bool _isCurrentLocation(int generation, String lease, String deliveryScope) {
    if (generation != _locationGeneration ||
        lease != _leaseKey ||
        deliveryScope != delivery?.id) {
      return false;
    }
    final snapshot = _access.snapshot;
    if (lease != BuyerDeliveriesViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return BuyerDeliveriesViewModel._hasPermission(snapshot) &&
        hasActiveDeliveryLocationScope;
  }

  static bool _supportsLiveLocation(String status) =>
      status == 'DISPATCHED' || status == 'IN_TRANSIT';

  Future<void> validateHandoff(String token) async {
    if (handoffStatus == BuyerHandoffStatus.validating ||
        receiptStatus == BuyerReceiptStatus.preparing ||
        receiptStatus == BuyerReceiptStatus.submitting ||
        _pendingReceipt != null) {
      return;
    }
    final lease = _leaseKey;
    final normalizedToken = token.trim();
    if (lease == null) {
      handoffStatus = BuyerHandoffStatus.unavailable;
      handoffMessage = 'Inicia sesión para verificar el código.';
      notifyListeners();
      return;
    }
    if (!canValidateHandoff) {
      handoffStatus = BuyerHandoffStatus.permissionDenied;
      handoffMessage = 'No tienes permiso para verificar este código.';
      notifyListeners();
      return;
    }
    if (normalizedToken.isEmpty || normalizedToken.length > 400) {
      handoff = null;
      _validatedToken = null;
      handoffStatus = BuyerHandoffStatus.invalid;
      handoffMessage = 'Ingresa un código válido de hasta 400 caracteres.';
      notifyListeners();
      return;
    }

    final generation = ++_commandGeneration;
    handoff = null;
    _validatedToken = null;
    handoffStatus = BuyerHandoffStatus.validating;
    handoffMessage = null;
    receipt = null;
    receiptStatus = BuyerReceiptStatus.idle;
    receiptMessage = null;
    notifyListeners();
    try {
      final result = await _repository.validateHandoff(normalizedToken);
      if (!_isCurrentCommand(generation, lease)) return;
      if (result.deliveryId != deliveryId) {
        handoffStatus = BuyerHandoffStatus.invalid;
        handoffMessage = 'El código pertenece a otra entrega.';
      } else {
        handoff = result;
        _validatedToken = normalizedToken;
        handoffStatus = BuyerHandoffStatus.verified;
        handoffMessage = 'Código verificado para esta entrega e intento.';
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentCommand(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        handoffStatus = BuyerHandoffStatus.permissionDenied;
        handoffMessage = 'No tienes permiso para verificar este código.';
      } else if (failure.statusCode == 400 ||
          failure.statusCode == 404 ||
          failure.statusCode == 409 ||
          failure.statusCode == 422) {
        handoffStatus = BuyerHandoffStatus.invalid;
        handoffMessage = 'El código es inválido, venció o no está activo.';
      } else {
        handoffStatus = BuyerHandoffStatus.unavailable;
        handoffMessage =
            'No se pudo confirmar el código. No se registró una recepción.';
      }
    } catch (_) {
      if (!_isCurrentCommand(generation, lease)) return;
      handoffStatus = BuyerHandoffStatus.unavailable;
      handoffMessage =
          'No se pudo confirmar el código. No se registró una recepción.';
    }
    notifyListeners();
  }

  Future<void> submitReceipt({
    required String decision,
    required String acceptedQuantity,
    String reason = '',
  }) async {
    if (_pendingReceipt != null) return;
    final lease = _leaseKey;
    if (lease == null) {
      receiptStatus = BuyerReceiptStatus.unavailable;
      receiptMessage = 'Inicia sesión para confirmar la recepción.';
      notifyListeners();
      return;
    }
    if (!canSubmitReceipt) {
      receiptStatus = BuyerReceiptStatus.permissionDenied;
      receiptMessage = handoff == null
          ? 'Verifica el código antes de registrar la recepción.'
          : 'No tienes permiso para registrar la recepción.';
      notifyListeners();
      return;
    }
    final normalizedDecision = decision.trim().toUpperCase();
    final normalizedReason = reason.trim();
    final quantity = num.tryParse(acceptedQuantity.trim());
    if (!{'ACCEPTED', 'DISPUTED'}.contains(normalizedDecision) ||
        quantity == null ||
        !quantity.isFinite ||
        quantity < 0 ||
        (normalizedDecision == 'DISPUTED' && normalizedReason.isEmpty) ||
        normalizedReason.length > 2000) {
      receiptStatus = BuyerReceiptStatus.conflict;
      receiptMessage =
          normalizedDecision == 'DISPUTED' && normalizedReason.isEmpty
          ? 'Explica la diferencia antes de enviarla.'
          : 'Revisa la cantidad y el motivo ingresados.';
      notifyListeners();
      return;
    }
    final validation = handoff!;
    final token = _validatedToken!;
    final snapshot = _access.snapshot;
    final scopeKey = snapshot.currentContext!.scopeKey;
    final tokenFingerprint = sha256.convert(utf8.encode(token)).toString();
    final payloadFingerprint = sha256
        .convert(
          utf8.encode(
            'buyer-receipt-v1|$deliveryId|${validation.attemptId}|'
            '$tokenFingerprint|$normalizedDecision|$quantity|'
            '${normalizedReason.isEmpty ? '<null>' : normalizedReason}',
          ),
        )
        .toString();
    final generation = ++_commandGeneration;
    receipt = null;
    receiptStatus = BuyerReceiptStatus.preparing;
    receiptMessage = null;
    notifyListeners();
    late final String idempotencyKey;
    try {
      idempotencyKey = await _receiptStore.keyFor(
        scopeKey: scopeKey,
        deliveryId: deliveryId,
        attemptId: validation.attemptId,
        payloadFingerprint: payloadFingerprint,
      );
    } on BuyerReceiptCommandConflict {
      if (!_isCurrentCommand(generation, lease)) return;
      receiptStatus = BuyerReceiptStatus.conflict;
      receiptMessage = 'Ya existe un envío pendiente o confirmado con otro contenido para este intento.';
      notifyListeners();
      return;
    } catch (_) {
      if (!_isCurrentCommand(generation, lease)) return;
      receiptStatus = BuyerReceiptStatus.unavailable;
      receiptMessage =
          'No se pudo preparar un reintento seguro. No se envió la recepción.';
      notifyListeners();
      return;
    }
    if (!_isCurrentCommand(generation, lease)) return;
    if (!canSubmitReceipt) {
      receiptStatus = BuyerReceiptStatus.permissionDenied;
      receiptMessage = 'No tienes permiso para registrar la recepción.';
      notifyListeners();
      return;
    }
    final command = _PendingBuyerReceipt(
      token: token,
      decision: normalizedDecision,
      acceptedQuantity: quantity,
      reason: normalizedReason.isEmpty ? null : normalizedReason,
      idempotencyKey: idempotencyKey,
      scopeKey: scopeKey,
      deliveryId: deliveryId,
      attemptId: validation.attemptId,
      payloadFingerprint: payloadFingerprint,
    );
    _pendingReceipt = command;
    await _sendReceipt(command, generation: generation, lease: lease);
  }

  Future<void> retryReceipt() async {
    final command = _pendingReceipt;
    final lease = _leaseKey;
    if (command == null || lease == null || !canSubmitReceipt) return;
    final generation = ++_commandGeneration;
    await _sendReceipt(command, generation: generation, lease: lease);
  }

  Future<void> _sendReceipt(
    _PendingBuyerReceipt command, {
    required int generation,
    required String lease,
  }) async {
    if (!_isCurrentCommand(generation, lease)) return;
    receiptStatus = BuyerReceiptStatus.submitting;
    receiptMessage = null;
    notifyListeners();
    try {
      final result = await _repository.recordReceipt(
        deliveryId: deliveryId,
        token: command.token,
        decision: command.decision,
        acceptedQuantity: command.acceptedQuantity,
        reason: command.reason,
        idempotencyKey: command.idempotencyKey,
      );
      if (!_isCurrentCommand(generation, lease)) return;
      final validation = handoff;
      if (validation == null ||
          result.deliveryId != deliveryId ||
          result.attemptId != validation.attemptId ||
          result.decision != command.decision ||
          result.acceptedQuantity != command.acceptedQuantity) {
        receiptStatus = BuyerReceiptStatus.unavailable;
        receiptMessage = 'La respuesta no coincide con el intento verificado. Reintenta el mismo envío.';
      } else {
        receipt = result;
        if (!receipts.any((value) => value.id == result.id)) {
          receipts = List.unmodifiable([...receipts, result]);
        }
        receiptStatus = result.decision == 'DISPUTED'
            ? BuyerReceiptStatus.disputed
            : BuyerReceiptStatus.accepted;
        receiptMessage = result.decision == 'DISPUTED'
            ? 'La diferencia quedó registrada junto con el resultado del conductor.'
            : 'La recepción quedó registrada de forma separada al resultado del conductor.';
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentCommand(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        receiptStatus = BuyerReceiptStatus.permissionDenied;
        receiptMessage = 'No tienes permiso para registrar la recepción.';
      } else if (failure.statusCode == 400 || failure.statusCode == 422) {
        try {
          await _receiptStore.release(
            scopeKey: command.scopeKey,
            deliveryId: command.deliveryId,
            attemptId: command.attemptId,
            payloadFingerprint: command.payloadFingerprint,
          );
          if (!_isCurrentCommand(generation, lease)) return;
          _pendingReceipt = null;
          receiptStatus = BuyerReceiptStatus.conflict;
          receiptMessage = 'El servidor rechazó la cantidad o el motivo. Corrígelos y vuelve a enviar.';
        } catch (_) {
          if (!_isCurrentCommand(generation, lease)) return;
          receiptStatus = BuyerReceiptStatus.conflict;
          receiptMessage = 'El servidor rechazó esta recepción y no se confirmó. Reabre la entrega antes de editarla.';
        }
      } else if (failure.statusCode == 409 || failure.statusCode == 412) {
        _pendingReceipt = null;
        handoff = null;
        _validatedToken = null;
        handoffStatus = BuyerHandoffStatus.idle;
        handoffMessage = null;
        receipt = null;
        receiptStatus = BuyerReceiptStatus.conflict;
        receiptMessage = 'El intento cambió o ya tiene una recepción distinta. Verifica de nuevo el código antes de continuar.';
      } else {
        receiptStatus = BuyerReceiptStatus.unavailable;
        receiptMessage = 'No se confirmó la recepción. Reintenta sin cambiar cantidad ni motivo.';
      }
    } catch (_) {
      if (!_isCurrentCommand(generation, lease)) return;
      receiptStatus = BuyerReceiptStatus.unavailable;
      receiptMessage = 'No se confirmó la recepción. Reintenta sin cambiar cantidad ni motivo.';
    }
    notifyListeners();
  }

  Future<void> uploadReceiptEvidence({
    required String receiptId,
    required String filename,
    required String contentType,
    required List<int> bytes,
  }) async {
    final lease = _leaseKey;
    final target = _receiptForEvidence(receiptId);
    if (lease == null) {
      evidenceReceiptId = receiptId;
      evidenceStatus = BuyerReceiptEvidenceStatus.unavailable;
      evidenceMessage = 'Inicia sesión para adjuntar evidencia.';
      notifyListeners();
      return;
    }
    if (target == null || !canAttachEvidenceTo(target)) {
      evidenceReceiptId = receiptId;
      evidenceStatus = BuyerReceiptEvidenceStatus.permissionDenied;
      evidenceMessage =
          'No tienes permiso para adjuntar evidencia a esta recepción.';
      notifyListeners();
      return;
    }
    final store = _evidenceStore;
    if (store == null) {
      evidenceReceiptId = receiptId;
      evidenceStatus = BuyerReceiptEvidenceStatus.unavailable;
      evidenceMessage = 'No se pudo preparar un envío seguro.';
      notifyListeners();
      return;
    }
    final normalizedFilename = filename.trim();
    final normalizedType = contentType.trim().toLowerCase();
    final allowedTypes = const {'image/jpeg', 'image/png', 'application/pdf'};
    if (normalizedFilename.isEmpty ||
        normalizedFilename.length > 255 ||
        normalizedFilename.contains('/') ||
        normalizedFilename.contains('\\') ||
        normalizedFilename.contains('\n') ||
        normalizedFilename.contains('\r') ||
        !allowedTypes.contains(normalizedType) ||
        bytes.isEmpty) {
      evidenceReceiptId = receiptId;
      evidenceStatus = BuyerReceiptEvidenceStatus.conflict;
      evidenceMessage = 'El archivo no tiene un formato admitido.';
      notifyListeners();
      return;
    }

    final snapshot = _access.snapshot;
    final scopeKey = snapshot.currentContext!.scopeKey;
    final bytesFingerprint = sha256.convert(bytes).toString();
    final payloadFingerprint = sha256
        .convert(
          utf8.encode(
            jsonEncode([
              'buyer-receipt-evidence-v1',
              receiptId,
              normalizedFilename,
              normalizedType,
              bytesFingerprint,
            ]),
          ),
        )
        .toString();
    final generation = ++_evidenceGeneration;
    evidenceReceiptId = receiptId;
    evidenceStatus = BuyerReceiptEvidenceStatus.preparing;
    evidenceMessage = null;
    notifyListeners();

    late final String idempotencyKey;
    try {
      idempotencyKey = await store.keyFor(
        scopeKey: scopeKey,
        receiptId: receiptId,
        payloadFingerprint: payloadFingerprint,
      );
    } catch (_) {
      if (!_isCurrentEvidence(generation, lease, receiptId)) return;
      evidenceStatus = BuyerReceiptEvidenceStatus.unavailable;
      evidenceMessage = 'No se pudo preparar un envío seguro.';
      notifyListeners();
      return;
    }
    if (!_isCurrentEvidence(generation, lease, receiptId)) return;
    if (!canAttachEvidenceTo(target)) {
      evidenceStatus = BuyerReceiptEvidenceStatus.permissionDenied;
      evidenceMessage =
          'No tienes permiso para adjuntar evidencia a esta recepción.';
      notifyListeners();
      return;
    }

    evidenceStatus = BuyerReceiptEvidenceStatus.uploading;
    notifyListeners();
    try {
      final result = await _repository.uploadReceiptEvidence(
        receiptId: receiptId,
        filename: normalizedFilename,
        contentType: normalizedType,
        bytes: bytes,
        idempotencyKey: idempotencyKey,
      );
      if (!_isCurrentEvidence(generation, lease, receiptId)) return;
      if (result.receiptId != receiptId) {
        evidenceStatus = BuyerReceiptEvidenceStatus.unavailable;
        evidenceMessage = 'El servidor no confirmó a qué recepción pertenece el archivo. Actualiza el historial.';
      } else {
        _replaceReceiptEvidence(receiptId, result);
        evidenceStatus = BuyerReceiptEvidenceStatus.uploaded;
        evidenceMessage = 'Evidencia registrada. El estado del análisis aparece en el historial.';
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentEvidence(generation, lease, receiptId)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        evidenceStatus = BuyerReceiptEvidenceStatus.permissionDenied;
        evidenceMessage =
            'No tienes permiso para adjuntar evidencia a esta recepción.';
      } else if (failure.statusCode == 409) {
        evidenceStatus = BuyerReceiptEvidenceStatus.conflict;
        evidenceMessage = 'El envío entra en conflicto con otro archivo. Actualiza el historial antes de continuar.';
      } else {
        evidenceStatus = BuyerReceiptEvidenceStatus.unavailable;
        evidenceMessage = 'No se confirmó el archivo. Selecciona el mismo archivo y reintenta, o actualiza el historial.';
      }
    } catch (_) {
      if (!_isCurrentEvidence(generation, lease, receiptId)) return;
      evidenceStatus = BuyerReceiptEvidenceStatus.unavailable;
      evidenceMessage = 'No se confirmó el archivo. Selecciona el mismo archivo y reintenta, o actualiza el historial.';
    }
    notifyListeners();
  }

  BuyerReceiptProjection? _receiptForEvidence(String receiptId) {
    for (final value in receipts) {
      if (value.id == receiptId) return value;
    }
    if (receipt?.id == receiptId) return receipt;
    return null;
  }

  void _replaceReceiptEvidence(
    String receiptId,
    BuyerReceiptEvidenceProjection evidence,
  ) {
    BuyerReceiptProjection update(BuyerReceiptProjection value) {
      if (value.id != receiptId) return value;
      final next = [
        ...value.evidence.where((item) => item.id != evidence.id),
        evidence,
      ];
      return value.withEvidence(next);
    }

    receipts = List.unmodifiable(receipts.map(update));
    if (receipt?.id == receiptId) receipt = update(receipt!);
  }

  bool _isCurrentEvidence(int generation, String lease, String receiptId) {
    if (generation != _evidenceGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != BuyerDeliveriesViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    final target = _receiptForEvidence(receiptId);
    return target != null && canAttachEvidenceTo(target);
  }

  bool _isCurrentCommand(int generation, String lease) {
    if (generation != _commandGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != BuyerDeliveriesViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  bool _isAuthorityFailure(NexaApiFailure failure) =>
      failure.statusCode == 401 ||
      failure.code == 'ACCESS_CONTEXT_INVALID' ||
      failure.code == 'authority_changed';

  bool _isCurrent(int generation, String lease) {
    if (generation != _requestGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != BuyerDeliveriesViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = BuyerDeliveriesViewModel._activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    _activeDetailRequest = null;
    _commandGeneration++;
    _evidenceGeneration++;
    _locationGeneration++;
    _locationExpiryTimer?.cancel();
    authorityRevision++;
    handoff = null;
    _validatedToken = null;
    _pendingReceipt = null;
    handoffStatus = BuyerHandoffStatus.idle;
    handoffMessage = null;
    receipt = null;
    receiptStatus = BuyerReceiptStatus.idle;
    receiptMessage = null;
    receipts = const [];
    evidenceReceiptId = null;
    evidenceStatus = BuyerReceiptEvidenceStatus.idle;
    evidenceMessage = null;
    location = null;
    locationStatus = BuyerLiveLocationStatus.inactive;
    locationMessage = null;
    _clear(
      snapshot.isSignedIn && !BuyerDeliveriesViewModel._hasPermission(snapshot)
          ? BuyerDeliveryDetailStatus.permissionDenied
          : BuyerDeliveryDetailStatus.unavailable,
      snapshot.isSignedIn && !BuyerDeliveriesViewModel._hasPermission(snapshot)
          ? 'No tienes permiso para consultar esta entrega.'
          : 'El contexto activo cambió. Vuelve a abrir esta entrega.',
    );
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    _activeDetailRequest = null;
    _commandGeneration++;
    _locationGeneration++;
    _locationExpiryTimer?.cancel();
    _clear(
      BuyerDeliveryDetailStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _access.invalidateLocalSession();
  }

  void _clear(BuyerDeliveryDetailStatus nextStatus, String? nextMessage) {
    _evidenceGeneration++;
    _locationGeneration++;
    _locationExpiryTimer?.cancel();
    _locationExpiryTimer = null;
    status = nextStatus;
    delivery = null;
    events = const [];
    receipts = const [];
    message = nextMessage;
    handoff = null;
    _validatedToken = null;
    _pendingReceipt = null;
    handoffStatus = BuyerHandoffStatus.idle;
    handoffMessage = null;
    receipt = null;
    receiptStatus = BuyerReceiptStatus.idle;
    receiptMessage = null;
    evidenceReceiptId = null;
    evidenceStatus = BuyerReceiptEvidenceStatus.idle;
    evidenceMessage = null;
    location = null;
    locationStatus = BuyerLiveLocationStatus.idle;
    locationMessage = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _requestGeneration++;
    _activeDetailRequest = null;
    _commandGeneration++;
    _evidenceGeneration++;
    _locationGeneration++;
    _locationExpiryTimer?.cancel();
    _subscription.cancel();
    super.dispose();
  }
}

final class _PendingBuyerReceipt {
  const _PendingBuyerReceipt({
    required this.token,
    required this.decision,
    required this.acceptedQuantity,
    required this.reason,
    required this.idempotencyKey,
    required this.scopeKey,
    required this.deliveryId,
    required this.attemptId,
    required this.payloadFingerprint,
  });

  final String token;
  final String decision;
  final num acceptedQuantity;
  final String? reason;
  final String idempotencyKey;
  final String scopeKey;
  final String deliveryId;
  final String attemptId;
  final String payloadFingerprint;
}
