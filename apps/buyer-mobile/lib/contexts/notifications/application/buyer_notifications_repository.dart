final class BuyerNotificationProjection {
  const BuyerNotificationProjection({
    required this.id,
    required this.category,
    required this.title,
    required this.message,
    required this.createdAt,
    this.deepLink,
    this.subjectType,
    this.subjectId,
    this.readAt,
  });

  final String id;
  final String category;
  final String title;
  final String message;
  final String? deepLink;
  final String? subjectType;
  final String? subjectId;
  final DateTime createdAt;
  final DateTime? readAt;

  bool get isRead => readAt != null;
}

final class BuyerNotificationInboxProjection {
  const BuyerNotificationInboxProjection({
    required this.items,
    required this.unreadCount,
    required this.limit,
  });

  final List<BuyerNotificationProjection> items;
  final int unreadCount;
  final int limit;
}

final class BuyerNotificationPreferenceProjection {
  const BuyerNotificationPreferenceProjection({
    required this.eventCategory,
    required this.channel,
    required this.enabled,
    required this.version,
  });

  final String eventCategory;
  final String channel;
  final bool enabled;
  final int version;
}

final class BuyerNotificationPreferencesProjection {
  const BuyerNotificationPreferencesProjection({
    required this.preferences,
    required this.version,
  });

  final List<BuyerNotificationPreferenceProjection> preferences;
  final int version;
}

abstract interface class BuyerNotificationsRepository {
  Future<BuyerNotificationInboxProjection> list({
    required bool unreadOnly,
    required int limit,
  });

  Future<void> markRead(String notificationId);

  Future<void> markUnread(String notificationId);

  Future<void> markAllRead();

  Future<BuyerNotificationPreferencesProjection> preferences();

  Future<BuyerNotificationPreferencesProjection> updatePreference({
    required BuyerNotificationPreferencesProjection current,
    required BuyerNotificationPreferenceProjection preference,
    required bool enabled,
  });
}
