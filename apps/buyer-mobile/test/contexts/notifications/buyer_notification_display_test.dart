import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/notifications/application/buyer_notifications_repository.dart';
import 'package:nexa_buyer_mobile/contexts/notifications/presentation/buyer_notification_display.dart';

void main() {
  test(
    'normalizes the exact generated delivery copy without completion claims',
    () {
      final display = buyerNotificationDisplay(_notification());

      expect(display.title, 'Actualización de entrega');
      expect(display.message, 'Hay una actualización sobre tu entrega.');
      expect(display.category, 'Estado del pedido');

      final unknownCategory = buyerNotificationDisplay(
        _notification(category: 'UNRECOGNIZED_CATEGORY'),
      );
      expect(unknownCategory.category, 'Notificación');
    },
  );

  test(
    'preserves server-authored copy and gives unknown categories a safe label',
    () {
      final display = buyerNotificationDisplay(
        _notification(
          title: 'Tu pedido necesita atención',
          message: 'Revisa la actualización que te enviamos.',
          category: 'UNRECOGNIZED_CATEGORY',
        ),
      );

      expect(display.title, 'Tu pedido necesita atención');
      expect(display.message, 'Revisa la actualización que te enviamos.');
      expect(display.category, 'Notificación');
    },
  );

  test('does not rewrite partial matches of the generated copy', () {
    final display = buyerNotificationDisplay(
      _notification(message: 'DeliveryCompleted v1 is now DELIVERED.'),
    );

    expect(display.title, 'DeliveryCompleted v1');
    expect(display.message, 'DeliveryCompleted v1 is now DELIVERED.');
    expect(display.category, 'Estado del pedido');
  });
}

BuyerNotificationProjection _notification({
  String title = 'DeliveryCompleted v1',
  String message = 'DeliveryCompleted v1 was recorded.',
  String category = 'ORDER_STATUS',
}) => BuyerNotificationProjection(
  id: '00000000-0000-4000-8000-000000000001',
  title: title,
  message: message,
  category: category,
  createdAt: DateTime.utc(2026, 11, 17),
);
