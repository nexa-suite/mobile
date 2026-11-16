import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_conversation_repository.dart';

final class BuyerConversationViewModel extends ChangeNotifier {
  BuyerConversationViewModel(this._repository, this._store, this._access) {
    _lease = _leaseOf(_access.snapshot);
    _subscription = _access.changes.listen(_accessChanged);
  }
  final BuyerConversationRepository _repository;
  final PendingBuyerMessageStore _store;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _lease, _cursor;
  bool _disposed = false, _loading = false, _sending = false;
  bool hasMore = true, initialized = false;
  String? message;
  PendingBuyerMessage? pending;
  List<BuyerMessage> items = [];
  bool get sending => _sending;
  bool get canSend =>
      _lease != null &&
      _access.snapshot.currentContext!.permissions.contains(
        'buyer.sales.write',
      );
  String? get _scope => _access.snapshot.hasActiveBuyerScope
      ? '${_access.snapshot.currentContext!.userId}|${_access.snapshot.currentContext!.scopeKey}'
      : null;
  static String? _leaseOf(BuyerAccessSnapshot s) =>
      s.hasActiveBuyerScope &&
          s.currentContext!.permissions.contains('buyer.sales.read')
      ? '${s.authorityEpoch}:${s.currentContext!.authorityFingerprint}'
      : null;
  bool _current(String lease) =>
      !_disposed && _lease == lease && _leaseOf(_access.snapshot) == lease;
  void _accessChanged(BuyerAccessSnapshot s) {
    final next = _leaseOf(s);
    if (next == _lease) return;
    _lease = next;
    _cursor = null;
    items = [];
    pending = null;
    hasMore = true;
    initialized = false;
    _loading = false;
    _sending = false;
    message = 'Tu acceso cambió. Vuelve a abrir la conversación.';
    if (!_disposed) notifyListeners();
  }

  Future<void> load() async {
    final lease = _lease, scope = _scope;
    if (lease == null || scope == null || _loading) return;
    _loading = true;
    try {
      if (!initialized) {
        final restored = await _store.read(scope);
        if (!_current(lease)) return;
        pending = restored;
      }
      final page = await _repository.list(_cursor);
      if (!_current(lease)) return;
      final known = items.map((m) => m.id).toSet();
      items = [...items, ...page.items.where((m) => known.add(m.id))];
      _cursor = page.nextCursor ?? _cursor;
      hasMore = page.hasMore;
      initialized = true;
      message = null;
    } on NexaApiFailure catch (failure) {
      if (_current(lease)) {
        if (failure.statusCode == 401 || failure.statusCode == 403) {
          items = [];
          pending = null;
          initialized = false;
          _cursor = null;
          message = 'No tienes acceso a esta conversación.';
          if (failure.statusCode == 401) _access.invalidateLocalSession();
        } else {
          message =
              'No se pudo actualizar la conversación. Vuelve a intentarlo.';
        }
      }
    } catch (_) {
      if (_current(lease)) {
        message = 'No se pudo actualizar la conversación. Vuelve a intentarlo.';
      }
    } finally {
      if (_current(lease)) {
        _loading = false;
        notifyListeners();
      }
    }
  }

  Future<bool> send(String body) async {
    final lease = _lease, scope = _scope;
    if (lease == null ||
        scope == null ||
        !canSend ||
        _sending ||
        !initialized) {
      return false;
    }
    final text = body.trim();
    if (pending == null && (text.isEmpty || text.length > 4000)) return false;
    _sending = true;
    message = null;
    notifyListeners();
    try {
      final command = pending ?? PendingBuyerMessage(text, const Uuid().v4());
      if (pending == null) {
        await _store.write(scope, command);
        if (!_current(lease)) return false;
        pending = command;
      }
      await _repository.send(command.body, command.key);
      if (!_current(lease)) return false;
      await _store.clear(scope);
      if (!_current(lease)) return false;
      pending = null;
      await load();
      return true;
    } on NexaApiFailure catch (failure) {
      if (_current(lease)) {
        if (failure.statusCode == 401 || failure.statusCode == 403) {
          items = [];
          pending = null;
          initialized = false;
          _cursor = null;
          message = 'No tienes acceso a esta conversación.';
          if (failure.statusCode == 401) _access.invalidateLocalSession();
        } else {
          message =
              'No se pudo confirmar el envío. Reintenta el mismo mensaje.';
        }
      }
    } catch (_) {
      if (_current(lease)) {
        message = 'No se pudo confirmar el envío. Reintenta el mismo mensaje.';
      }
      return false;
    } finally {
      if (_current(lease)) {
        _sending = false;
        notifyListeners();
      }
    }
    return false;
  }

  @override
  void dispose() {
    _disposed = true;
    _subscription.cancel();
    super.dispose();
  }
}
