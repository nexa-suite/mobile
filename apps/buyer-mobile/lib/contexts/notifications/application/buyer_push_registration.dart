import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:uuid/uuid.dart';

import '../../tenant_access_governance/application/buyer_access_repository.dart';

abstract interface class BuyerPushDevice {
  Stream<String> get registrations;
  Stream<void> get foregroundMessages;
  Stream<void> get openedMessages;
  Future<bool> register();
  Future<void> reset();
  Future<bool> wasOpenedFromNotification();
}

abstract interface class BuyerPushRegistrationGateway {
  Future<String> register({
    required BuyerAccessContext context,
    required String installationId,
    required String platform,
    required String destination,
    required String commandKey,
  });
}

/// Push only invalidates the inbox; every displayed business fact comes from Nexa.
final class BuyerPushRegistration {
  BuyerPushRegistration({
    required this.device,
    required this.gateway,
    required this.access,
    required this.platform,
    required this.refreshInbox,
    required this.openInbox,
    this.retryDelay = const Duration(seconds: 30),
  });

  final BuyerPushDevice device;
  final BuyerPushRegistrationGateway gateway;
  final BuyerAccessRepository access;
  final String platform;
  final Future<void> Function() refreshInbox;
  final void Function() openInbox;
  final Duration retryDelay;
  final List<StreamSubscription<Object?>> _subscriptions = [];
  Future<void> _tail = Future.value();
  Timer? _retry;
  String? _scope;
  String? _fid;
  String? _registeredDestination;
  String? _commandKey;
  bool _disposed = false;
  bool _pendingOpen = false;
  String? _pendingOpenLease;
  bool _opening = false;
  int _openGeneration = 0;
  bool _resetRequired = true;

  void start() {
    _subscriptions.add(
      access.changes.listen((_) {
        final lease = _lease();
        if (_pendingOpenLease != null && _pendingOpenLease != lease) {
          _pendingOpen = false;
          _pendingOpenLease = null;
          _openGeneration++;
        }
        _scheduleOpen();
        refresh();
      }),
    );
    _subscriptions.add(
      device.registrations.listen((fid) {
        if (fid.isEmpty || fid.length > 128) return;
        _enqueue(() async {
          if (_scope == null || _scope != _lease() || _resetRequired) return;
          if (_fid != fid) {
            _fid = fid;
            _commandKey = const Uuid().v4();
          }
          await _registerDestination();
        });
      }),
    );
    _subscriptions.add(
      device.foregroundMessages.listen((_) => _enqueue(_refreshIfAuthorized)),
    );
    _subscriptions.add(
      device.openedMessages.listen((_) {
        _requestOpen(_lease());
      }),
    );
    final initialLease = _lease();
    final initialGeneration = _openGeneration;
    unawaited(_readInitialOpen(initialLease, initialGeneration));
    refresh();
  }

  /// Also called on foreground return, so failed registrations can recover.
  void refresh() {
    _scheduleOpen();
    _enqueue(_synchronize);
  }

  String? _lease() {
    final snapshot = access.snapshot;
    if (!snapshot.hasActiveBuyerScope ||
        !snapshot.currentContext!.permissions.contains('notification.read')) {
      return null;
    }
    return '${snapshot.authorityEpoch}|'
        '${snapshot.currentContext!.authorityFingerprint}';
  }

  void _enqueue(Future<void> Function() operation) {
    if (_disposed) return;
    _tail = _tail.then((_) async {
      if (_disposed) return;
      try {
        await operation();
      } catch (_) {
        // A provider failure never blocks authentication or implies delivery.
        _retry?.cancel();
        if (_lease() != null) {
          _retry = Timer(retryDelay, refresh);
        }
      }
    });
  }

  Future<void> _synchronize() async {
    final lease = _lease();
    if (lease != _scope) {
      _retry?.cancel();
      _registeredDestination = null;
      _commandKey = null;
      _fid = null;
      _scope = lease;
      _resetRequired = true;
    }
    if (_resetRequired) {
      // Also reset on process startup: no previous account's installation may
      // be reactivated after a logout interrupted by process death.
      await device.reset();
      _resetRequired = false;
    }
    if (lease == null || lease != _lease()) return;
    if (!await device.register()) return;
    if (lease != _lease()) return;
    await _registerDestination();
  }

  Future<void> _registerDestination() async {
    final scope = _scope;
    final fid = _fid;
    if (scope == null || scope != _lease() || fid == null) return;
    final destination = 'fcm-fid:$fid';
    if (_registeredDestination == destination) return;
    _commandKey ??= const Uuid().v4();
    await gateway.register(
      context: access.snapshot.currentContext!,
      installationId: 'buyer-${sha256.convert(utf8.encode(fid))}',
      platform: platform,
      destination: destination,
      commandKey: _commandKey!,
    );
    if (scope == _lease()) {
      _registeredDestination = destination;
      _retry?.cancel();
    }
  }

  Future<void> _refreshIfAuthorized() async {
    if (_lease() != null && _scope == _lease()) await refreshInbox();
  }

  Future<void> _readInitialOpen(String? lease, int generation) async {
    try {
      final opened = await device.wasOpenedFromNotification();
      if (!_disposed &&
          opened &&
          generation == _openGeneration &&
          (lease == null || lease == _lease())) {
        _requestOpen(lease);
      }
    } catch (_) {
      // A provider failure must not affect authentication or inbox access.
    }
  }

  void _requestOpen(String? lease) {
    if (_disposed) return;
    _openGeneration++;
    if (_opening && _pendingOpenLease == lease) return;
    _pendingOpen = true;
    _pendingOpenLease = lease;
    _scheduleOpen();
  }

  void _scheduleOpen() {
    if (_disposed || _opening || !_pendingOpen) return;
    final lease = _lease();
    if (lease == null) return;
    if (_pendingOpenLease != null && _pendingOpenLease != lease) {
      _pendingOpen = false;
      _pendingOpenLease = null;
      return;
    }
    _pendingOpenLease = lease;
    _opening = true;
    unawaited(_openInbox(lease));
  }

  Future<void> _openInbox(String lease) async {
    try {
      await refreshInbox();
      if (!_disposed &&
          _pendingOpen &&
          _pendingOpenLease == lease &&
          lease == _lease()) {
        _pendingOpen = false;
        _pendingOpenLease = null;
        openInbox();
      }
    } catch (_) {
      // Leave an authorized request pending for a later access or resume event.
    } finally {
      _opening = false;
      if (!_disposed && _pendingOpen && _pendingOpenLease != lease) {
        _scheduleOpen();
      }
    }
  }

  Future<void> dispose() async {
    _disposed = true;
    _retry?.cancel();
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    _subscriptions.clear();
  }
}
