import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_purchase_workspace_repository.dart';
import '../application/purchase_request_repository.dart';

String? _lease(BuyerAccessSnapshot snapshot) => snapshot.hasActiveBuyerScope
    ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
    : null;

final class BuyerRequestsViewModel extends ChangeNotifier {
  BuyerRequestsViewModel(this._workspace, this._drafts, this._access)
    : _authority = _lease(_access.snapshot) {
    _subscription = _access.changes.listen((snapshot) {
      final next = _lease(snapshot);
      if (next == _authority) {
        return;
      }
      _authority = next;
      _generation++;
      requests = const [];
      drafts = const [];
      page = 0;
      totalPages = 1;
      message = null;
      busy = false;
      notifyListeners();
    });
  }
  final BuyerPurchaseWorkspaceRepository _workspace;
  final PurchaseRequestRepository _drafts;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _authority;
  int _generation = 0;
  bool busy = false;
  bool showingDrafts = false;
  int page = 0;
  int totalPages = 1;
  String? statusFilter;
  String? message;
  List<BuyerRequestProjection> requests = const [];
  List<PurchaseRequestDraftSummaryProjection> drafts = const [];

  bool get canRead =>
      _authority != null &&
      _authority == _lease(_access.snapshot) &&
      _access.snapshot.hasActiveBuyerScope &&
      _access.snapshot.currentContext!.permissions.contains('buyer.sales.read');
  bool get canWrite =>
      canRead &&
      _access.snapshot.currentContext!.permissions.contains(
        'buyer.sales.write',
      );

  Future<void> showDrafts(bool value) async {
    if (busy || value == showingDrafts) {
      return;
    }
    showingDrafts = value;
    page = 0;
    requests = const [];
    drafts = const [];
    await refresh();
  }

  Future<void> filter(String? value) async {
    if (busy) {
      return;
    }
    statusFilter = value;
    page = 0;
    requests = const [];
    await refresh();
  }

  Future<void> refresh() => load(page);
  Future<void> load(int target) async {
    if (busy || target < 0) {
      return;
    }
    if (!canRead || _authority == null) {
      requests = const [];
      drafts = const [];
      message = 'Tu acceso no permite consultar solicitudes.';
      notifyListeners();
      return;
    }
    final authority = _authority;
    final generation = ++_generation;
    busy = true;
    message = null;
    notifyListeners();
    try {
      if (showingDrafts) {
        final result = await _drafts.listDrafts(page: target);
        if (authority != _authority ||
            authority != _lease(_access.snapshot) ||
            generation != _generation) {
          return;
        }
        drafts = result.items;
        totalPages = result.totalPages;
      } else {
        final result = await _workspace.listRequests(
          page: target,
          status: statusFilter,
        );
        if (authority != _authority ||
            authority != _lease(_access.snapshot) ||
            generation != _generation) {
          return;
        }
        requests = result.items;
        totalPages = (result.total / result.size).ceil();
      }
      page = target;
    } on NexaApiFailure catch (error) {
      if (authority != _authority ||
          authority != _lease(_access.snapshot) ||
          generation != _generation) {
        return;
      }
      if (error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      requests = const [];
      drafts = const [];
      message = error.userMessage;
    } catch (_) {
      if (authority != _authority ||
          authority != _lease(_access.snapshot) ||
          generation != _generation) {
        return;
      }
      message = 'No se pudieron consultar tus solicitudes.';
      requests = const [];
      drafts = const [];
    }
    if (authority != _authority ||
        authority != _lease(_access.snapshot) ||
        generation != _generation) {
      return;
    }
    busy = false;
    notifyListeners();
  }

  @override
  void dispose() {
    _generation++;
    _subscription.cancel();
    super.dispose();
  }
}

final class BuyerRequestDetailViewModel extends ChangeNotifier {
  BuyerRequestDetailViewModel(this._repository, this._access, this.id)
    : _authority = _lease(_access.snapshot) {
    _subscription = _access.changes.listen((snapshot) {
      final next = _lease(snapshot);
      if (next == _authority) {
        return;
      }
      _authority = next;
      _generation++;
      request = null;
      proposal = null;
      events = const [];
      eventsMessage = null;
      busy = false;
      message = 'El acceso cambió. Abre nuevamente la solicitud.';
      notifyListeners();
    });
  }
  final BuyerPurchaseWorkspaceRepository _repository;
  final BuyerAccessRepository _access;
  final String id;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _authority;
  int _generation = 0;
  bool busy = false;
  String? message;
  String? eventsMessage;
  BuyerRequestProjection? request;
  BuyerMaterialChange? proposal;
  List<BuyerRequestEvent> events = const [];
  bool get canWrite =>
      _authority != null &&
      _authority == _lease(_access.snapshot) &&
      _access.snapshot.hasActiveBuyerScope &&
      _access.snapshot.currentContext!.permissions.containsAll({
        'buyer.sales.read',
        'buyer.sales.write',
      });
  bool get canWithdraw =>
      canWrite &&
      !busy &&
      {'DRAFT', 'SUBMITTED', 'CHANGES_PROPOSED'}.contains(request?.status);
  bool get canDecide =>
      canWrite &&
      !busy &&
      request?.status == 'CHANGES_PROPOSED' &&
      proposal?.status == 'PROPOSED' &&
      proposal!.requestVersion == request!.version;

  Future<void> load() async {
    if (busy || _authority == null) {
      return;
    }
    final snapshot = _access.snapshot;
    if (!snapshot.hasActiveBuyerScope ||
        !snapshot.currentContext!.permissions.contains('buyer.sales.read')) {
      return;
    }
    final authority = _authority;
    final generation = ++_generation;
    busy = true;
    message = null;
    eventsMessage = null;
    notifyListeners();
    try {
      final result = await _repository.request(id);
      if (!_current(generation, authority)) {
        return;
      }
      request = result;
      proposal = null;
      events = const [];
      if (result.status == 'CHANGES_PROPOSED') {
        final loadedProposal = await _repository.currentChange(id);
        if (!_current(generation, authority)) {
          return;
        }
        proposal = loadedProposal;
      }
      try {
        final loadedEvents = await _repository.events(id);
        if (!_current(generation, authority)) {
          return;
        }
        events = loadedEvents;
      } on NexaApiFailure catch (error) {
        if (!_current(generation, authority)) {
          return;
        }
        if (error.statusCode == 401 || error.statusCode == 403) {
          rethrow;
        }
        eventsMessage =
            'El historial no está disponible. Puedes volver a actualizar.';
      } on Object {
        if (!_current(generation, authority)) {
          return;
        }
        eventsMessage =
            'El historial no está disponible. Puedes volver a actualizar.';
      }
    } on NexaApiFailure catch (error) {
      if (!_current(generation, authority)) {
        return;
      }
      if (error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      request = null;
      proposal = null;
      events = const [];
      message = error.userMessage;
    } catch (_) {
      if (!_current(generation, authority)) {
        return;
      }
      request = null;
      proposal = null;
      events = const [];
      message = 'No se pudo consultar esta solicitud.';
    }
    if (!_current(generation, authority)) {
      return;
    }
    busy = false;
    notifyListeners();
  }

  Future<void> decide(BuyerRequestDecision decision) async {
    if (busy || !canWrite || request == null || _authority == null) {
      return;
    }
    if (decision == BuyerRequestDecision.withdraw ? !canWithdraw : !canDecide) {
      return;
    }
    final authority = _authority;
    final generation = ++_generation;
    final original = request!;
    final scope = _access.snapshot.currentContext!.scopeKey;
    busy = true;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.decide(
        request: original,
        decision: decision,
        scopeKey: scope,
        proposal: proposal,
      );
      if (!_current(generation, authority)) {
        return;
      }
      request = result;
      proposal = null;
      message = 'Nexa confirmó tu decisión.';
    } on NexaApiFailure catch (error) {
      if (!_current(generation, authority)) {
        return;
      }
      if (error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      message = error.statusCode == null || error.statusCode! >= 500
          ? 'No se confirmó el resultado. Actualiza para consultar el estado antes de decidir de nuevo.'
          : error.userMessage;
      // Re-read before exposing another action; never replay a changed version.
      request = null;
      proposal = null;
    } catch (_) {
      if (!_current(generation, authority)) {
        return;
      }
      message =
          'No se confirmó el resultado. Actualiza el estado de la solicitud.';
      request = null;
      proposal = null;
    }
    if (!_current(generation, authority)) {
      return;
    }
    busy = false;
    notifyListeners();
  }

  bool _current(int generation, String? authority) =>
      generation == _generation &&
      authority == _authority &&
      authority == _lease(_access.snapshot);
  @override
  void dispose() {
    _generation++;
    _subscription.cancel();
    super.dispose();
  }
}
