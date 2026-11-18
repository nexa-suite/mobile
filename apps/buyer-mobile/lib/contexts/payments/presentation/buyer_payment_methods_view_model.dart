import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_payment_methods.dart';

final class BuyerPaymentMethodsViewModel extends ChangeNotifier {
  BuyerPaymentMethodsViewModel(
    this._repository,
    this._store,
    this._setupPort,
    this._access,
  ) {
    _lease = _leaseOf(_access.snapshot);
    _subscription = _access.changes.listen(_accessChanged);
  }
  final BuyerPaymentMethodsRepository _repository;
  final BuyerSetupKeyStore _store;
  final BuyerPaymentSetupPort _setupPort;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease;
  bool _disposed = false, busy = false, initialized = false;
  List<BuyerPaymentMethod> methods = [];
  String? message;
  bool get canEdit =>
      _lease != null &&
      _access.snapshot.currentContext!.permissions.contains('payment.create');
  String? get _scope => _access.snapshot.hasActiveBuyerScope
      ? '${_access.snapshot.currentContext!.userId}|${_access.snapshot.currentContext!.scopeKey}'
      : null;
  static String? _leaseOf(BuyerAccessSnapshot s) =>
      s.hasActiveBuyerScope &&
          s.currentContext!.permissions.contains('payment.read')
      ? '${s.authorityEpoch}:${s.currentContext!.authorityFingerprint}'
      : null;
  bool _current(String lease) =>
      !_disposed && _lease == lease && _leaseOf(_access.snapshot) == lease;
  void _accessChanged(BuyerAccessSnapshot s) {
    final next = _leaseOf(s);
    if (_lease == next) return;
    _lease = next;
    methods = [];
    initialized = false;
    busy = false;
    message = 'Tu acceso cambió. Vuelve a abrir medios de pago.';
    if (!_disposed) notifyListeners();
  }

  Future<void> load() async {
    final lease = _lease;
    if (lease == null || busy) return;
    busy = true;
    notifyListeners();
    try {
      final result = await _repository.list();
      if (!_current(lease)) return;
      methods = result;
      initialized = true;
      message = null;
    } on NexaApiFailure catch (failure) {
      if (!_current(lease)) return;
      if (failure.statusCode == 401 || failure.statusCode == 403) {
        methods = [];
        initialized = false;
        message = 'No tienes acceso a medios de pago.';
        if (failure.statusCode == 401) _access.invalidateLocalSession();
      } else {
        message = 'No se pudieron actualizar los medios de pago.';
      }
    } catch (_) {
      if (_current(lease)) {
        message = 'No se pudieron actualizar los medios de pago.';
      }
    } finally {
      if (_current(lease)) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> setup() async {
    final lease = _lease, scope = _scope;
    if (lease == null || scope == null || !canEdit || busy || !initialized) {
      return;
    }
    busy = true;
    message = null;
    notifyListeners();
    final previousMethodIds = methods.map((method) => method.id).toSet();
    try {
      var key = await _store.read(scope);
      if (!_current(lease)) return;
      if (key == null ||
          !RegExp(
            r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
          ).hasMatch(key)) {
        if (key != null) await _store.clear(scope);
        if (!_current(lease)) return;
        key = const Uuid().v4();
        await _store.write(scope, key);
        if (!_current(lease)) return;
      }
      final setup = await _repository.setup(key);
      if (!_current(lease)) return;
      final status = setup.status.toLowerCase();
      if (status == 'succeeded') {
        await _store.clear(scope);
        if (!_current(lease)) return;
        await _refreshAfterSetup(
          lease,
          previousMethodIds,
          successMessage: 'El proveedor confirmó el registro. Actualiza para ver la tarjeta.',
          pendingMessage: 'El proveedor confirmó el registro. Actualiza para ver la tarjeta.',
        );
      } else if (status == 'canceled' || status == 'cancelled') {
        await _store.clear(scope);
        if (!_current(lease)) return;
        message = 'El intento fue cancelado. Puedes iniciar uno nuevo.';
      } else {
        final outcome = await _setupPort.present(setup, () => _current(lease));
        if (!_current(lease)) return;
        if (outcome == BuyerSetupOutcome.completed) {
          await _refreshAfterSetup(
            lease,
            previousMethodIds,
            successMessage: 'Nexa confirmó la tarjeta. Ya aparece en tu lista.',
            pendingMessage: 'Registro enviado. La tarjeta aparecerá cuando Nexa confirme al proveedor.',
          );
        } else if (outcome == BuyerSetupOutcome.cancelled) {
          message = 'Registro cancelado. Puedes continuar el mismo intento.';
        } else {
          message =
              'No se pudo confirmar el registro. Reintenta el mismo intento.';
        }
      }
    } catch (_) {
      if (_current(lease)) {
        message =
            'No se pudo confirmar el registro. Reintenta el mismo intento.';
      }
    } finally {
      if (_current(lease)) {
        busy = false;
        notifyListeners();
      }
    }
  }

  Future<void> _refreshAfterSetup(
    String lease,
    Set<String> previousMethodIds, {
    required String successMessage,
    required String pendingMessage,
  }) async {
    try {
      final result = await _repository.list();
      if (!_current(lease)) return;
      methods = result;
      initialized = true;
      message = result.any((method) => !previousMethodIds.contains(method.id))
          ? successMessage
          : pendingMessage;
    } on NexaApiFailure catch (failure) {
      if (!_current(lease)) return;
      if (failure.statusCode == 401 || failure.statusCode == 403) {
        methods = [];
        initialized = false;
        message = 'No tienes acceso a medios de pago.';
        if (failure.statusCode == 401) _access.invalidateLocalSession();
      } else {
        message = pendingMessage;
      }
    } catch (_) {
      if (_current(lease)) message = pendingMessage;
    }
  }

  Future<void> mutate(String id, {required bool remove}) async {
    final lease = _lease;
    if (lease == null ||
        !canEdit ||
        busy ||
        !methods.any((method) => method.id == id)) {
      return;
    }
    busy = true;
    message = null;
    notifyListeners();
    var unknown = false;
    try {
      if (remove) {
        await _repository.remove(id);
      } else {
        await _repository.makeDefault(id);
      }
    } catch (_) {
      unknown = true;
    }
    if (!_current(lease)) return;
    try {
      final result = await _repository.list();
      if (!_current(lease)) return;
      methods = result;
      message = unknown
          ? 'No se pudo confirmar la operación. Se muestran los datos actuales.'
          : null;
    } catch (_) {
      if (!_current(lease)) return;
      methods = [];
      initialized = false;
      message =
          'No se pudo confirmar la operación. Actualiza antes de continuar.';
    } finally {
      if (_current(lease)) {
        busy = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _subscription.cancel();
    super.dispose();
  }
}
