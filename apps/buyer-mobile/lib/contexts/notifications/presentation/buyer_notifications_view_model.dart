import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_notifications_repository.dart';

enum BuyerNotificationsStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

enum BuyerNotificationFilter { all, unread }

final class BuyerNotificationsViewModel extends ChangeNotifier {
  BuyerNotificationsViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  static const readPermission = 'notification.read';
  static const managePreferencesPermission = 'notification.manage_preferences';
  static const inboxLimit = 25;

  final BuyerNotificationsRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _inboxGeneration = 0;
  int _preferencesGeneration = 0;

  BuyerNotificationsStatus inboxStatus = BuyerNotificationsStatus.idle;
  BuyerNotificationFilter filter = BuyerNotificationFilter.all;
  List<BuyerNotificationProjection> notifications = const [];
  int unreadCount = 0;
  String? inboxMessage;
  final Set<String> pendingNotificationIds = {};
  bool markAllPending = false;

  BuyerNotificationsStatus preferencesStatus =
      BuyerNotificationsStatus.idle;
  BuyerNotificationPreferencesProjection? notificationPreferences;
  String? preferencesMessage;
  final Set<String> pendingPreferenceKeys = {};

  bool get canRead => _hasPermission(_access.snapshot, readPermission);

  bool get canManagePreferences =>
      _hasPermission(_access.snapshot, managePreferencesPermission);

  Future<void> refreshInbox() async {
    if (markAllPending || pendingNotificationIds.isNotEmpty) return;
    final lease = _leaseKey;
    if (lease == null) {
      _clearInbox(
        BuyerNotificationsStatus.unavailable,
        'Inicia sesión para consultar notificaciones.',
      );
      notifyListeners();
      return;
    }
    if (!canRead) {
      _inboxGeneration++;
      _clearInbox(
        BuyerNotificationsStatus.permissionDenied,
        'No tienes permiso para consultar notificaciones.',
      );
      notifyListeners();
      return;
    }
    final generation = ++_inboxGeneration;
    inboxStatus = BuyerNotificationsStatus.loading;
    inboxMessage = null;
    notifyListeners();
    try {
      final result = await _repository.list(
        unreadOnly: filter == BuyerNotificationFilter.unread,
        limit: inboxLimit,
      );
      if (!_isCurrentInbox(generation, lease)) return;
      notifications = result.items;
      unreadCount = result.unreadCount;
      inboxStatus = BuyerNotificationsStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentInbox(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      notifications = const [];
      unreadCount = 0;
      inboxStatus = failure.statusCode == 403
          ? BuyerNotificationsStatus.permissionDenied
          : BuyerNotificationsStatus.unavailable;
      inboxMessage = failure.statusCode == 403
          ? 'No tienes permiso para consultar notificaciones.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrentInbox(generation, lease)) return;
      notifications = const [];
      unreadCount = 0;
      inboxStatus = BuyerNotificationsStatus.unavailable;
      inboxMessage = 'No se pudieron consultar las notificaciones.';
    }
    notifyListeners();
  }

  Future<void> setFilter(BuyerNotificationFilter value) async {
    if (filter == value) return;
    filter = value;
    await refreshInbox();
  }

  Future<void> setRead(BuyerNotificationProjection notification, bool read) async {
    final lease = _leaseKey;
    if (lease == null ||
        !canRead ||
        markAllPending ||
        pendingNotificationIds.isNotEmpty ||
        notification.isRead == read) {
      return;
    }
    final generation = ++_inboxGeneration;
    pendingNotificationIds.add(notification.id);
    inboxMessage = null;
    notifyListeners();
    try {
      if (read) {
        await _repository.markRead(notification.id);
      } else {
        await _repository.markUnread(notification.id);
      }
      if (!_isCurrentInbox(generation, lease)) return;
      pendingNotificationIds.remove(notification.id);
      await refreshInbox();
      return;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentInbox(generation, lease)) return;
      pendingNotificationIds.remove(notification.id);
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      inboxMessage = failure.statusCode == 403
          ? 'No tienes permiso para cambiar el estado de lectura.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrentInbox(generation, lease)) return;
      pendingNotificationIds.remove(notification.id);
      inboxMessage = 'No se pudo guardar el estado de lectura.';
    }
    notifyListeners();
  }

  Future<void> markAllRead() async {
    final lease = _leaseKey;
    if (lease == null ||
        !canRead ||
        markAllPending ||
        pendingNotificationIds.isNotEmpty ||
        unreadCount == 0) {
      return;
    }
    final generation = ++_inboxGeneration;
    markAllPending = true;
    inboxMessage = null;
    notifyListeners();
    try {
      await _repository.markAllRead();
      if (!_isCurrentInbox(generation, lease)) return;
      markAllPending = false;
      await refreshInbox();
      return;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentInbox(generation, lease)) return;
      markAllPending = false;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      inboxMessage = failure.statusCode == 403
          ? 'No tienes permiso para cambiar el estado de lectura.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrentInbox(generation, lease)) return;
      markAllPending = false;
      inboxMessage = 'No se pudieron marcar como leídas.';
    }
    notifyListeners();
  }

  Future<void> refreshPreferences() async {
    if (pendingPreferenceKeys.isNotEmpty) return;
    final lease = _leaseKey;
    if (lease == null) {
      _clearPreferences(
        BuyerNotificationsStatus.unavailable,
        'Inicia sesión para consultar preferencias.',
      );
      notifyListeners();
      return;
    }
    if (!canRead) {
      _preferencesGeneration++;
      _clearPreferences(
        BuyerNotificationsStatus.permissionDenied,
        'No tienes permiso para consultar preferencias.',
      );
      notifyListeners();
      return;
    }
    final generation = ++_preferencesGeneration;
    preferencesStatus = BuyerNotificationsStatus.loading;
    preferencesMessage = null;
    notifyListeners();
    try {
      final result = await _repository.preferences();
      if (!_isCurrentPreferences(generation, lease)) return;
      notificationPreferences = result;
      preferencesStatus = BuyerNotificationsStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentPreferences(generation, lease)) return;
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      notificationPreferences = null;
      preferencesStatus = failure.statusCode == 403
          ? BuyerNotificationsStatus.permissionDenied
          : BuyerNotificationsStatus.unavailable;
      preferencesMessage = failure.statusCode == 403
          ? 'No tienes permiso para consultar preferencias.'
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrentPreferences(generation, lease)) return;
      notificationPreferences = null;
      preferencesStatus = BuyerNotificationsStatus.unavailable;
      preferencesMessage = 'No se pudieron consultar las preferencias.';
    }
    notifyListeners();
  }

  Future<void> setPreference(
    BuyerNotificationPreferenceProjection preference,
    bool enabled,
  ) async {
    final lease = _leaseKey;
    final current = notificationPreferences;
    final key = _preferenceKey(preference);
    if (lease == null ||
        !canManagePreferences ||
        current == null ||
        !current.preferences.any(
          (item) =>
              _preferenceKey(item) == key && item.version == preference.version,
        ) ||
        pendingPreferenceKeys.isNotEmpty ||
        preference.enabled == enabled ||
        !_supportedChannel(preference.channel)) {
      return;
    }
    final generation = ++_preferencesGeneration;
    pendingPreferenceKeys.add(key);
    preferencesMessage = null;
    notifyListeners();
    try {
      final result = await _repository.updatePreference(
        current: current,
        preference: preference,
        enabled: enabled,
      );
      if (!_isCurrentPreferences(generation, lease)) return;
      notificationPreferences = result;
      preferencesStatus = BuyerNotificationsStatus.current;
      pendingPreferenceKeys.remove(key);
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentPreferences(generation, lease)) return;
      pendingPreferenceKeys.remove(key);
      if (_isAuthorityFailure(failure)) {
        _invalidateCurrentAuthority();
        return;
      }
      if (failure.statusCode == 409 || failure.statusCode == 412) {
        notificationPreferences = null;
        preferencesMessage =
            'Las preferencias cambiaron. Actualiza antes de volver a intentar.';
        preferencesStatus = BuyerNotificationsStatus.unavailable;
      } else if (failure.statusCode == 403) {
        notificationPreferences = null;
        preferencesMessage = 'No tienes permiso para cambiar preferencias.';
        preferencesStatus = BuyerNotificationsStatus.permissionDenied;
      } else {
        notificationPreferences = null;
        preferencesMessage =
            'No se confirmó el cambio. Actualiza para consultar el estado.';
        preferencesStatus = BuyerNotificationsStatus.unavailable;
      }
    } catch (_) {
      if (!_isCurrentPreferences(generation, lease)) return;
      pendingPreferenceKeys.remove(key);
      notificationPreferences = null;
      preferencesStatus = BuyerNotificationsStatus.unavailable;
      preferencesMessage =
          'No se confirmó el cambio. Actualiza para consultar el estado.';
    }
    notifyListeners();
  }

  bool _isCurrentInbox(int generation, String lease) =>
      _isCurrent(generation, lease, _inboxGeneration);

  bool _isCurrentPreferences(int generation, String lease) =>
      _isCurrent(generation, lease, _preferencesGeneration);

  bool _isCurrent(int generation, String lease, int currentGeneration) {
    if (generation != currentGeneration || lease != _leaseKey) return false;
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
    _inboxGeneration++;
    _preferencesGeneration++;
    _clearInbox(BuyerNotificationsStatus.idle, null);
    _clearPreferences(BuyerNotificationsStatus.idle, null);
    notifyListeners();
  }

  void _invalidateCurrentAuthority() {
    _inboxGeneration++;
    _preferencesGeneration++;
    _clearInbox(
      BuyerNotificationsStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    _clearPreferences(
      BuyerNotificationsStatus.unavailable,
      'El acceso activo cambió. Inicia sesión nuevamente.',
    );
    notifyListeners();
    _access.invalidateLocalSession();
  }

  void _clearInbox(BuyerNotificationsStatus status, String? message) {
    inboxStatus = status;
    notifications = const [];
    unreadCount = 0;
    inboxMessage = message;
    pendingNotificationIds.clear();
    markAllPending = false;
  }

  void _clearPreferences(BuyerNotificationsStatus status, String? message) {
    preferencesStatus = status;
    notificationPreferences = null;
    preferencesMessage = message;
    pendingPreferenceKeys.clear();
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

  static String _preferenceKey(
    BuyerNotificationPreferenceProjection preference,
  ) =>
      '${preference.eventCategory}|${preference.channel}';

  static bool _supportedChannel(String channel) =>
      channel == 'IN_APP' || channel == 'EMAIL';

  @override
  void dispose() {
    _inboxGeneration++;
    _preferencesGeneration++;
    _subscription.cancel();
    super.dispose();
  }
}
