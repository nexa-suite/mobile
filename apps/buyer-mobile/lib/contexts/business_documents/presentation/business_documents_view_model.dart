import '../application/business_document_export_port.dart';

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/business_documents_repository.dart';

enum BusinessDocumentsStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BusinessDocumentsViewModel extends ChangeNotifier {
  BusinessDocumentsViewModel(
    this._repository,
    this._access, {
    this.subjectType,
    this.subjectId,
  }) : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final String? subjectType;
  final String? subjectId;

  static const pageSize = 25;
  static const readPermission = 'document.read';

  final BusinessDocumentsRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BusinessDocumentsStatus status = BusinessDocumentsStatus.idle;
  List<BusinessDocumentProjection> items = const [];
  int page = 0;
  int total = 0;
  String? message;

  Future<void> refresh() => _load(page);

  Future<void> nextPage() {
    if (status != BusinessDocumentsStatus.current ||
        (page + 1) * pageSize >= total) {
      return Future.value();
    }
    return _load(page + 1);
  }

  Future<void> previousPage() {
    if (status != BusinessDocumentsStatus.current || page == 0) {
      return Future.value();
    }
    return _load(page - 1);
  }

  Future<void> _load(int requestedPage) async {
    final lease = _leaseKey;
    if (lease == null) return;
    if (!_hasPermission(_access.snapshot, readPermission)) {
      status = BusinessDocumentsStatus.permissionDenied;
      items = const [];
      message = 'No tienes permiso para consultar documentos.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    status = BusinessDocumentsStatus.loading;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.list(
        page: requestedPage,
        subjectType: subjectType,
        subjectId: subjectId,
      );
      if (!_isCurrent(generation, lease)) return;
      items = result.items;
      page = result.page;
      total = result.total;
      status = BusinessDocumentsStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      items = const [];
      status = failure.statusCode == 403
          ? BusinessDocumentsStatus.permissionDenied
          : BusinessDocumentsStatus.unavailable;
      message = failure.statusCode == 403
          ? 'No tienes permiso para consultar documentos.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      items = const [];
      status = BusinessDocumentsStatus.unavailable;
      message = 'No se pudieron consultar los documentos.';
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
    status = BusinessDocumentsStatus.idle;
    items = const [];
    page = 0;
    total = 0;
    message = null;
    notifyListeners();
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    status = BusinessDocumentsStatus.unavailable;
    items = const [];
    page = 0;
    total = 0;
    message = 'El acceso activo cambió. Inicia sesión nuevamente.';
    notifyListeners();
    _access.invalidateLocalSession();
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

  @override
  void dispose() {
    _requestGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}

enum BusinessDocumentDetailStatus {
  loading,
  current,
  unavailable,
  permissionDenied,
}

enum BusinessDocumentDownloadStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class BusinessDocumentDetailViewModel extends ChangeNotifier {
  BusinessDocumentDetailViewModel(
    this._repository,
    this._access,
    this.documentId,
  ) : _leaseKey = BusinessDocumentsViewModel._activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const readPermission = 'document.read';
  static const downloadPermission = 'document.download';

  final BusinessDocumentsRepository _repository;
  final BuyerAccessRepository _access;
  final String documentId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BusinessDocumentDetailStatus status = BusinessDocumentDetailStatus.loading;
  BusinessDocumentDownloadStatus downloadStatus =
      BusinessDocumentDownloadStatus.idle;
  BusinessDocumentProjection? document;
  BusinessDocumentContentProjection? content;
  String? message;

  bool get canDownload {
    final current = document;
    final snapshot = _access.snapshot;
    return current != null &&
        current.contentAvailable &&
        current.byteSize <= maxProtectedBusinessDocumentBytes &&
        _hasPermission(snapshot, readPermission) &&
        _hasPermission(snapshot, downloadPermission);
  }

  bool get downloadCapabilityGranted =>
      _hasPermission(_access.snapshot, downloadPermission);

  bool get downloadExceedsLimit =>
      document != null &&
      document!.byteSize > maxProtectedBusinessDocumentBytes;

  Future<void> load() async {
    final lease = _leaseKey;
    if (lease == null) {
      _denyRead();
      return;
    }
    if (!_hasPermission(_access.snapshot, readPermission)) {
      _denyRead();
      return;
    }
    final generation = ++_requestGeneration;
    status = BusinessDocumentDetailStatus.loading;
    downloadStatus = BusinessDocumentDownloadStatus.idle;
    document = null;
    _clearContent();
    message = null;
    notifyListeners();
    try {
      final result = await _repository.detail(documentId);
      if (!_isCurrent(generation, lease)) return;
      document = result;
      status = BusinessDocumentDetailStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      status = failure.statusCode == 403
          ? BusinessDocumentDetailStatus.permissionDenied
          : BusinessDocumentDetailStatus.unavailable;
      message = failure.statusCode == 403
          ? 'No tienes permiso para consultar este documento.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = BusinessDocumentDetailStatus.unavailable;
      message = 'Este documento no está disponible.';
    }
    notifyListeners();
  }

  Future<void> download() async {
    final lease = _leaseKey;
    final current = document;
    if (lease == null || current == null) return;
    if (!_hasPermission(_access.snapshot, readPermission) ||
        !_hasPermission(_access.snapshot, downloadPermission)) {
      _clearContent();
      downloadStatus = BusinessDocumentDownloadStatus.permissionDenied;
      message = 'No tienes permiso para descargar este documento.';
      notifyListeners();
      return;
    }
    if (!current.contentAvailable ||
        current.byteSize > maxProtectedBusinessDocumentBytes) {
      _clearContent();
      downloadStatus = BusinessDocumentDownloadStatus.unavailable;
      message = current.byteSize > maxProtectedBusinessDocumentBytes
          ? 'El documento supera el límite de descarga protegido.'
          : 'El documento todavía no está listo para descargar.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    downloadStatus = BusinessDocumentDownloadStatus.loading;
    _clearContent();
    message = null;
    notifyListeners();
    try {
      final result = await _repository.download(current);
      if (!_isCurrent(generation, lease) ||
          !_hasPermission(_access.snapshot, readPermission) ||
          !_hasPermission(_access.snapshot, downloadPermission)) {
        result.clear();
        return;
      }
      if (result.document.id != current.id) {
        result.clear();
        throw const NexaApiFailure(
          code: 'document_identity_changed',
          userMessage: 'This document changed. Refresh before downloading.',
        );
      }
      content = result;
      downloadStatus = BusinessDocumentDownloadStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      _clearContent();
      downloadStatus = failure.statusCode == 403
          ? BusinessDocumentDownloadStatus.permissionDenied
          : BusinessDocumentDownloadStatus.unavailable;
      message = _downloadFailureMessage(failure);
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      _clearContent();
      downloadStatus = BusinessDocumentDownloadStatus.unavailable;
      message = 'El contenido no se pudo verificar.';
    }
    notifyListeners();
  }

  bool exporting = false;
  String? exportMessage;

  Future<void> saveDocument(BusinessDocumentExportPort exporter) async {
    final value = content;
    final lease = _leaseKey;
    if (exporting || value == null || lease == null || !canDownload) return;
    final generation = _requestGeneration;
    exporting = true;
    exportMessage = null;
    notifyListeners();
    try {
      final saved = await exporter.save(value);
      if (!_isCurrent(generation, lease)) return;
      exportMessage = saved
          ? 'Documento guardado en la ubicación elegida.'
          : 'No se guardó el documento.';
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      exportMessage = 'No pudimos guardar el documento. Vuelve a intentarlo.';
    } finally {
      exporting = false;
      if (_isCurrent(generation, lease)) notifyListeners();
    }
  }

  void discardContent() {
    _clearContent();
    downloadStatus = BusinessDocumentDownloadStatus.idle;
    notifyListeners();
  }

  String _downloadFailureMessage(NexaApiFailure failure) {
    if (failure.statusCode == 403) {
      return 'No tienes permiso para descargar este documento.';
    }
    if (failure.statusCode == 400 && failure.code == 'INVALID_REQUEST') {
      return 'El archivo todavía no está disponible para descargar. Inténtalo más tarde.';
    }
    return failure.userMessage;
  }

  bool _isCurrent(int generation, String lease) {
    if (generation != _requestGeneration || lease != _leaseKey) return false;
    final snapshot = _access.snapshot;
    if (lease != BusinessDocumentsViewModel._activeLease(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  void _denyRead() {
    _requestGeneration++;
    status = BusinessDocumentDetailStatus.permissionDenied;
    document = null;
    _clearContent();
    downloadStatus = BusinessDocumentDownloadStatus.permissionDenied;
    message = 'No tienes permiso para consultar documentos.';
    notifyListeners();
  }

  void _invalidateCurrentAuthority() {
    _requestGeneration++;
    status = BusinessDocumentDetailStatus.unavailable;
    downloadStatus = BusinessDocumentDownloadStatus.permissionDenied;
    document = null;
    _clearContent();
    message = 'El acceso activo cambió. Inicia sesión nuevamente.';
    notifyListeners();
    _access.invalidateLocalSession();
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = BusinessDocumentsViewModel._activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    status = snapshot.isSignedIn
        ? BusinessDocumentDetailStatus.unavailable
        : BusinessDocumentDetailStatus.permissionDenied;
    downloadStatus = BusinessDocumentDownloadStatus.idle;
    document = null;
    _clearContent();
    message = snapshot.isSignedIn
        ? 'Tu contexto de acceso cambió. Actualiza el documento para continuar.'
        : 'Inicia sesión para consultar este documento.';
    notifyListeners();
  }

  static bool _hasPermission(BuyerAccessSnapshot snapshot, String permission) =>
      snapshot.isSignedIn &&
      snapshot.currentContext!.permissions.contains(permission);

  static bool _isAuthorityFailure(NexaApiFailure failure) =>
      failure.statusCode == 401 || failure.code == 'ACCESS_CONTEXT_INVALID';

  void _clearContent() {
    exportMessage = null;
    content?.clear();
    content = null;
  }

  @override
  void dispose() {
    _requestGeneration++;
    _clearContent();
    document = null;
    _subscription.cancel();
    super.dispose();
  }
}
