import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_notifications_repository.dart';

final class BuyerNotificationsRepositoryImpl
    implements BuyerNotificationsRepository {
  BuyerNotificationsRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<BuyerNotificationInboxProjection> list({
    required bool unreadOnly,
    required int limit,
  }) async {
    if (limit < 1 || limit > 100) _invalidResponse();
    final response = await _api.get(
      '/notifications',
      query: {'unread': '$unreadOnly', 'limit': '$limit'},
      refreshAfterUnauthorized: true,
    );
    final rawItems = response.body['items'];
    final unreadCount = _integer(response.body['unreadCount']);
    final responseLimit = _integer(response.body['limit']);
    if (rawItems is! List ||
        unreadCount == null ||
        unreadCount < 0 ||
        responseLimit != limit ||
        rawItems.length > limit) {
      _invalidResponse();
    }
    final items = rawItems.map(_parseNotification).toList(growable: false);
    if (items.map((item) => item.id).toSet().length != items.length ||
        (unreadOnly && items.any((item) => item.isRead))) {
      _invalidResponse();
    }
    return BuyerNotificationInboxProjection(
      items: items,
      unreadCount: unreadCount,
      limit: responseLimit!,
    );
  }

  @override
  Future<void> markRead(String notificationId) async {
    _requireNotificationId(notificationId);
    await _api.post('/notifications/${Uri.encodeComponent(notificationId)}/read');
  }

  @override
  Future<void> markUnread(String notificationId) async {
    _requireNotificationId(notificationId);
    await _api.request(
      'DELETE',
      '/notifications/${Uri.encodeComponent(notificationId)}/read',
    );
  }

  @override
  Future<void> markAllRead() async {
    await _api.post('/notifications/read-all');
  }

  @override
  Future<BuyerNotificationPreferencesProjection> preferences() async {
    final response = await _api.get(
      '/notifications/preferences',
      refreshAfterUnauthorized: true,
    );
    return _parsePreferences(response.body);
  }

  @override
  Future<BuyerNotificationPreferencesProjection> updatePreference({
    required BuyerNotificationPreferencesProjection current,
    required BuyerNotificationPreferenceProjection preference,
    required bool enabled,
  }) async {
    if (!_supportedChannel(preference.channel) ||
        !current.preferences.any(
          (entry) =>
              entry.eventCategory == preference.eventCategory &&
              entry.channel == preference.channel &&
              entry.version == preference.version,
        )) {
      _invalidResponse();
    }
    final response = await _api.request(
      'PATCH',
      '/notifications/preferences',
      body: {
        'version': current.version,
        'preferences': [
          {
            'eventCategory': preference.eventCategory,
            'channel': preference.channel,
            'enabled': enabled,
            'version': preference.version,
          },
        ],
      },
    );
    final updated = _parsePreferences(response.body);
    final changed = updated.preferences.where(
      (entry) =>
          entry.eventCategory == preference.eventCategory &&
          entry.channel == preference.channel,
    );
    if (changed.length != 1 ||
        changed.single.enabled != enabled ||
        changed.single.version <= preference.version) {
      _invalidResponse();
    }
    return updated;
  }

  BuyerNotificationProjection _parseNotification(Object? value) {
    final notification = _map(value);
    final id = _string(notification?['id']);
    final category = _string(notification?['category']);
    final title = _string(notification?['title']);
    final message = _string(notification?['message']);
    final createdAt = _dateTime(notification?['createdAt']);
    final rawReadAt = notification?['readAt'];
    final readAt = rawReadAt == null ? null : _dateTime(rawReadAt);
    final subjectType = _string(notification?['subjectType']);
    final subjectId = _string(notification?['subjectId']);
    if (id == null ||
        !_isUuid(id) ||
        category == null ||
        title == null ||
        message == null ||
        createdAt == null ||
        (rawReadAt != null && readAt == null) ||
        (notification?['subjectType'] != null && subjectType == null) ||
        (notification?['subjectId'] != null && subjectId == null)) {
      _invalidResponse();
    }
    return BuyerNotificationProjection(
      id: id,
      category: category,
      title: title,
      message: message,
      deepLink: _string(notification?['deepLink']),
      subjectType: subjectType,
      subjectId: subjectId,
      createdAt: createdAt,
      readAt: readAt,
    );
  }

  BuyerNotificationPreferencesProjection _parsePreferences(
    Map<String, Object?> body,
  ) {
    final rawPreferences = body['preferences'];
    final version = _integer(body['version']);
    if (rawPreferences is! List || version == null || version < 0) {
      _invalidResponse();
    }
    final preferences = rawPreferences
        .map(_parsePreference)
        .toList(growable: false);
    final keys = preferences
        .map((item) => '${item.eventCategory}|${item.channel}')
        .toSet();
    if (keys.length != preferences.length) _invalidResponse();
    return BuyerNotificationPreferencesProjection(
      preferences: preferences,
      version: version,
    );
  }

  BuyerNotificationPreferenceProjection _parsePreference(Object? value) {
    final preference = _map(value);
    final eventCategory = _string(preference?['eventCategory']);
    final channel = _string(preference?['channel']);
    final enabled = preference?['enabled'];
    final version = _integer(preference?['version']);
    if (eventCategory == null ||
        eventCategory.length > 100 ||
        channel == null ||
        channel.length > 40 ||
        enabled is! bool ||
        version == null ||
        version < 0) {
      _invalidResponse();
    }
    return BuyerNotificationPreferenceProjection(
      eventCategory: eventCategory,
      channel: channel,
      enabled: enabled,
      version: version,
    );
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.trim().isNotEmpty ? value : null;

  int? _integer(Object? value) {
    if (value is int) return value;
    if (value is num && value.isFinite && value == value.roundToDouble()) {
      return value.toInt();
    }
    return null;
  }

  DateTime? _dateTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;

  bool _supportedChannel(String channel) =>
      channel == 'IN_APP' || channel == 'EMAIL';

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    caseSensitive: false,
  ).hasMatch(value);

  void _requireNotificationId(String notificationId) {
    if (!_isUuid(notificationId)) _invalidResponse();
  }

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_notification_response',
    userMessage: 'No se pudo leer la información de notificaciones.',
  );
}
