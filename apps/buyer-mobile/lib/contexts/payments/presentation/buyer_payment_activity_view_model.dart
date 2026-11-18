import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../../core/application/evidence_upload_policy.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_payments_repository.dart';
import '../application/buyer_transfer_proof_repository.dart';
import '../application/payment_report_idempotency_store.dart';

enum BuyerPaymentHistoryStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

enum BuyerTransferReportStatus {
  idle,
  preparing,
  submitting,
  reported,
  uncertain,
  unavailable,
  permissionDenied,
}

enum BuyerTransferProofStatus {
  idle,
  uploading,
  checking,
  pending,
  available,
  rejected,
  unavailable,
  uncertain,
}

final class BuyerPaymentActivityViewModel extends ChangeNotifier {
  BuyerPaymentActivityViewModel(
    this._repository,
    this._access,
    this._idempotencyStore,
    this.receivableId, {
    this.expectedClientAccountId,
  }) : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const pageSize = 25;
  static const readPermission = 'payment.read';
  static const createPermission = 'payment.create';

  final BuyerPaymentsRepository _repository;
  final BuyerAccessRepository _access;
  final PaymentReportIdempotencyStore _idempotencyStore;
  final String receivableId;
  final String? expectedClientAccountId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _historyGeneration = 0;
  int _reportGeneration = 0;
  int _proofGeneration = 0;

  BuyerPaymentHistoryStatus historyStatus = BuyerPaymentHistoryStatus.idle;
  BuyerPaymentHistoryPageProjection? history;
  String? historyMessage;
  BuyerTransferReportStatus reportStatus = BuyerTransferReportStatus.idle;
  BuyerPaymentProjection? reportedPayment;
  String? reportMessage;
  BuyerTransferProofProjection? proof;
  String? proofFilename;
  String? proofMessage;
  BuyerTransferProofStatus proofStatus = BuyerTransferProofStatus.idle;
  String? get proofSelectionLease => canReport ? _leaseKey : null;

  bool get isPreparingProof =>
      proofStatus == BuyerTransferProofStatus.uploading ||
      proofStatus == BuyerTransferProofStatus.checking;
  bool get canSubmitReport =>
      canReport &&
      !isReporting &&
      !isPreparingProof &&
      (proofFilename == null ||
          proofStatus == BuyerTransferProofStatus.available);

  void removeProof() {
    if (isReporting || isPreparingProof) return;
    _proofGeneration++;
    proof = null;
    proofFilename = null;
    proofMessage = null;
    proofStatus = BuyerTransferProofStatus.idle;
    notifyListeners();
  }

  Future<void> uploadTransferProof({
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String selectionLease,
  }) async {
    final lease = _leaseKey;
    if (lease == null ||
        lease != selectionLease ||
        !canReport ||
        isReporting ||
        isPreparingProof) {
      return;
    }
    if (bytes.isEmpty || bytes.length > evidenceUploadMaximumBytes) {
      proofMessage = 'Selecciona un archivo de hasta 10 MiB.';
      notifyListeners();
      return;
    }
    final generation = ++_proofGeneration;
    proof = null;
    proofFilename = filename;
    proofStatus = BuyerTransferProofStatus.uploading;
    proofMessage = 'Subiendo comprobante para su validación…';
    notifyListeners();
    var dispatched = false;
    try {
      final fingerprint = sha256
          .convert(
            utf8.encode(
              jsonEncode([
                filename.trim(),
                contentType,
                sha256.convert(bytes).toString(),
              ]),
            ),
          )
          .toString();
      final key = await _idempotencyStore.keyFor(
        scopeKey: '${_access.snapshot.currentContext!.scopeKey}|transfer-proof',
        receivableId: receivableId,
        referenceFingerprint: fingerprint,
      );
      if (!_isCurrentProof(generation, lease) || !canReport) return;
      dispatched = true;
      final result = await _repository.uploadTransferProof(
        receivableId: receivableId,
        filename: filename,
        contentType: contentType,
        bytes: bytes,
        idempotencyKey: key,
      );
      if (!_isCurrentProof(generation, lease)) return;
      _setProof(result);
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentProof(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      _proofFailure(failure, dispatched);
    } catch (_) {
      if (!_isCurrentProof(generation, lease)) return;
      _proofFailure(null, dispatched);
    }
    notifyListeners();
  }

  Future<void> refreshProof() async {
    final lease = _leaseKey;
    final current = proof;
    if (lease == null ||
        !canReport ||
        current == null ||
        isReporting ||
        isPreparingProof) {
      return;
    }
    final generation = ++_proofGeneration;
    proofStatus = BuyerTransferProofStatus.checking;
    notifyListeners();
    try {
      final result = await _repository.transferProof(
        receivableId: receivableId,
        evidenceId: current.id,
      );
      if (!_isCurrentProof(generation, lease)) return;
      _setProof(result);
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentProof(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode != null && failure.statusCode! < 500) {
        proof = null;
        _proofFailure(failure, false);
      } else {
        _proofStatusCheckUnavailable();
      }
    } catch (_) {
      if (!_isCurrentProof(generation, lease)) return;
      _proofStatusCheckUnavailable();
    }
    notifyListeners();
  }

  void _proofStatusCheckUnavailable() {
    proofStatus = BuyerTransferProofStatus.unavailable;
    proofMessage = 'No se pudo consultar el comprobante. Actualiza su estado para continuar.';
  }

  void _setProof(BuyerTransferProofProjection value) {
    proof = value;
    proofFilename = value.filename;
    proofStatus = value.isAvailable
        ? BuyerTransferProofStatus.available
        : value.isRejected
        ? BuyerTransferProofStatus.rejected
        : BuyerTransferProofStatus.pending;
    proofMessage = value.isAvailable
        ? 'Comprobante validado. Se adjuntará al reporte.'
        : value.isRejected
        ? 'El servidor rechazó o eliminó el archivo. Selecciona otro comprobante.'
        : 'El servidor está validando el archivo. Actualiza su estado antes de reportar.';
  }

  void _proofFailure(NexaApiFailure? failure, bool dispatched) {
    final uncertain =
        dispatched &&
        (failure?.statusCode == null || failure!.statusCode! >= 500);
    proofStatus = uncertain
        ? BuyerTransferProofStatus.uncertain
        : BuyerTransferProofStatus.unavailable;
    proofMessage = uncertain
        ? 'No se confirmó la carga. Vuelve a seleccionar el mismo archivo para reintentar sin duplicarlo.'
        : failure?.statusCode == 403
        ? 'No tienes permiso para adjuntar este comprobante.'
        : 'No se pudo validar el comprobante. Vuelve a seleccionar el archivo.';
  }

  bool _isCurrentProof(int generation, String lease) =>
      generation == _proofGeneration &&
      _isCurrentReport(_reportGeneration, lease);

  bool get canRead => _hasPermission(_access.snapshot, readPermission);
  bool get canReport => _hasPermission(_access.snapshot, createPermission);
  bool get isReporting =>
      reportStatus == BuyerTransferReportStatus.preparing ||
      reportStatus == BuyerTransferReportStatus.submitting;

  Future<void> refreshHistory() => loadHistory(history?.page ?? 0);

  Future<void> nextHistoryPage() {
    final current = history;
    if (historyStatus != BuyerPaymentHistoryStatus.current ||
        current == null ||
        (current.page + 1) * current.size >= current.total) {
      return Future.value();
    }
    return loadHistory(current.page + 1);
  }

  Future<void> previousHistoryPage() {
    final current = history;
    if (historyStatus != BuyerPaymentHistoryStatus.current ||
        current == null ||
        current.page == 0) {
      return Future.value();
    }
    return loadHistory(current.page - 1);
  }

  Future<void> loadHistory(int page) async {
    final lease = _leaseKey;
    if (lease == null) {
      _clearHistory(
        BuyerPaymentHistoryStatus.unavailable,
        'Inicia sesión para consultar el historial de pagos.',
      );
      return;
    }
    if (!canRead) {
      _historyGeneration++;
      _clearHistory(
        BuyerPaymentHistoryStatus.permissionDenied,
        'No tienes permiso para consultar el historial de pagos.',
      );
      return;
    }
    if (page < 0) {
      _clearHistory(
        BuyerPaymentHistoryStatus.unavailable,
        'No se pudo abrir esta página del historial.',
      );
      return;
    }
    final generation = ++_historyGeneration;
    historyStatus = BuyerPaymentHistoryStatus.loading;
    history = null;
    historyMessage = null;
    notifyListeners();
    try {
      final result = await _repository.listForReceivable(
        receivableId: receivableId,
        page: page,
        expectedClientAccountId: expectedClientAccountId,
      );
      if (!_isCurrentHistory(generation, lease)) return;
      history = result;
      historyStatus = BuyerPaymentHistoryStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentHistory(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      _clearHistory(
        failure.statusCode == 403
            ? BuyerPaymentHistoryStatus.permissionDenied
            : BuyerPaymentHistoryStatus.unavailable,
        failure.statusCode == 403
            ? 'No tienes permiso para consultar el historial de pagos.'
            : failure.userMessage,
      );
      return;
    } catch (_) {
      if (!_isCurrentHistory(generation, lease)) return;
      _clearHistory(
        BuyerPaymentHistoryStatus.unavailable,
        'No se pudo consultar el historial de pagos.',
      );
      return;
    }
    notifyListeners();
  }

  Future<void> reportBankTransfer(String reference) async {
    final lease = _leaseKey;
    if (lease == null) {
      reportStatus = BuyerTransferReportStatus.unavailable;
      reportMessage = 'Inicia sesión para reportar una transferencia.';
      notifyListeners();
      return;
    }
    if (!canReport) {
      reportStatus = BuyerTransferReportStatus.permissionDenied;
      reportedPayment = null;
      reportMessage = 'No tienes permiso para reportar transferencias.';
      notifyListeners();
      return;
    }
    if (isReporting) return;
    if (isPreparingProof ||
        (proofFilename != null &&
            proofStatus != BuyerTransferProofStatus.available)) {
      reportMessage =
          'Espera la validación del comprobante o retíralo del reporte.';
      notifyListeners();
      return;
    }
    final normalizedReference = reference.trim();
    if (normalizedReference.isEmpty || normalizedReference.length > 160) {
      reportStatus = BuyerTransferReportStatus.unavailable;
      reportedPayment = null;
      reportMessage =
          'Ingresa una referencia de transferencia de hasta 160 caracteres.';
      notifyListeners();
      return;
    }
    if (!_isUuid(receivableId)) {
      reportStatus = BuyerTransferReportStatus.unavailable;
      reportedPayment = null;
      reportMessage = 'No se pudo identificar la cuenta por cobrar.';
      notifyListeners();
      return;
    }

    final generation = ++_reportGeneration;
    final context = _access.snapshot.currentContext!;
    final referenceFingerprint = sha256
        .convert(utf8.encode(normalizedReference))
        .toString();
    reportStatus = BuyerTransferReportStatus.preparing;
    reportedPayment = null;
    reportMessage = null;
    notifyListeners();
    var dispatched = false;
    try {
      final idempotencyKey = await _idempotencyStore.keyFor(
        scopeKey: context.scopeKey,
        receivableId: receivableId,
        referenceFingerprint: referenceFingerprint,
      );
      if (!_isCurrentReport(generation, lease)) return;
      if (!canReport) {
        reportStatus = BuyerTransferReportStatus.permissionDenied;
        reportMessage = 'No tienes permiso para reportar transferencias.';
        notifyListeners();
        return;
      }
      reportStatus = BuyerTransferReportStatus.submitting;
      notifyListeners();
      dispatched = true;
      final payment = await _repository.reportBankTransfer(
        receivableId: receivableId,
        reference: normalizedReference,
        idempotencyKey: idempotencyKey,
        proofEvidenceId: proof?.id,
      );
      if (!_isCurrentReport(generation, lease)) return;
      reportedPayment = payment;
      reportStatus = BuyerTransferReportStatus.reported;
      reportMessage =
          'El servidor recibió el reporte. Estado actual: ${payment.status}. '
          'Reportar una transferencia no confirma que el pago se haya aplicado.';
      notifyListeners();
      if (canRead) await loadHistory(history?.page ?? 0);
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentReport(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 403) {
        reportStatus = BuyerTransferReportStatus.permissionDenied;
        reportMessage = 'No tienes permiso para reportar esta transferencia. Tu sesión sigue activa.';
      } else if (!dispatched) {
        reportStatus = BuyerTransferReportStatus.unavailable;
        reportMessage = 'No se pudo preparar un reintento seguro; no se envió ningún reporte.';
      } else if (failure.statusCode != null && failure.statusCode! < 500) {
        reportStatus = BuyerTransferReportStatus.unavailable;
        reportMessage = 'El servidor no aceptó el reporte. Revisa la referencia y el estado de la cuenta.';
      } else {
        reportStatus = BuyerTransferReportStatus.uncertain;
        reportMessage = 'No se pudo confirmar el resultado. Revisa el historial y, si reintentas, usa la misma referencia.';
      }
      notifyListeners();
    } catch (_) {
      if (!_isCurrentReport(generation, lease)) return;
      reportStatus = dispatched
          ? BuyerTransferReportStatus.uncertain
          : BuyerTransferReportStatus.unavailable;
      reportMessage = dispatched
          ? 'No se pudo confirmar el resultado. Revisa el historial y, si reintentas, usa la misma referencia.'
          : 'No se pudo preparar un reintento seguro; no se envió ningún reporte.';
      notifyListeners();
    }
  }

  bool _isCurrentHistory(int generation, String lease) {
    if (generation != _historyGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != _activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  bool _isCurrentReport(int generation, String lease) {
    if (generation != _reportGeneration || lease != _leaseKey) return false;
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
    _historyGeneration++;
    _reportGeneration++;
    _clearProof();
    _clearHistory(
      snapshot.isSignedIn && !_hasPermission(snapshot, readPermission)
          ? BuyerPaymentHistoryStatus.permissionDenied
          : BuyerPaymentHistoryStatus.idle,
      snapshot.isSignedIn && !_hasPermission(snapshot, readPermission)
          ? 'No tienes permiso para consultar el historial de pagos.'
          : null,
      notify: false,
    );
    reportStatus = BuyerTransferReportStatus.idle;
    reportedPayment = null;
    reportMessage = null;
    notifyListeners();
  }

  void _invalidateCurrentAuthority() {
    _historyGeneration++;
    _reportGeneration++;
    _clearProof();
    _clearHistory(
      BuyerPaymentHistoryStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
      notify: false,
    );
    reportStatus = BuyerTransferReportStatus.unavailable;
    reportedPayment = null;
    reportMessage = 'El acceso activo cambió. Inicia sesión nuevamente.';
    _access.invalidateLocalSession();
    notifyListeners();
  }

  void _clearProof() {
    _proofGeneration++;
    proof = null;
    proofFilename = null;
    proofMessage = null;
    proofStatus = BuyerTransferProofStatus.idle;
  }

  void _clearHistory(
    BuyerPaymentHistoryStatus status,
    String? message, {
    bool notify = true,
  }) {
    historyStatus = status;
    history = null;
    historyMessage = message;
    if (notify) notifyListeners();
  }

  static bool _hasPermission(BuyerAccessSnapshot snapshot, String permission) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.permissions.contains(permission);

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  static bool _isAuthorityFailure(NexaApiFailure failure) =>
      failure.statusCode == 401 || failure.code == 'ACCESS_CONTEXT_INVALID';

  static bool _isUuid(String value) => RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    caseSensitive: false,
  ).hasMatch(value);

  @override
  void dispose() {
    _historyGeneration++;
    _reportGeneration++;
    _proofGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}
