import '../application/buyer_notifications_repository.dart';

final class BuyerNotificationDisplay {
  const BuyerNotificationDisplay({
    required this.title,
    required this.message,
    required this.category,
  });

  final String title;
  final String message;
  final String category;
}

BuyerNotificationDisplay buyerNotificationDisplay(
  BuyerNotificationProjection notification,
) {
  if (notification.title == 'DeliveryCompleted v1' &&
      notification.message == 'DeliveryCompleted v1 was recorded.') {
    return BuyerNotificationDisplay(
      title: 'Actualización de entrega',
      message: 'Hay una actualización sobre tu entrega.',
      category: buyerNotificationCategoryLabel(notification.category),
    );
  }
  return BuyerNotificationDisplay(
    title: notification.title,
    message: notification.message,
    category: buyerNotificationCategoryLabel(notification.category),
  );
}

String buyerNotificationCategoryLabel(String category) => switch (category) {
  'TEMPERATURE_ALERT' => 'Alerta de temperatura',
  'DOCUMENT_REMINDER' => 'Recordatorio de documento',
  'ORDER_STATUS' => 'Estado del pedido',
  'INVITATION' => 'Invitación',
  _ => 'Notificación',
};
