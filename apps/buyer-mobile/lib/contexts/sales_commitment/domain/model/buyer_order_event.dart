final class BuyerOrderEvent {
  const BuyerOrderEvent({
    required this.id,
    required this.type,
    required this.occurredAt,
    this.fromStatus,
    this.toStatus,
  });
  final String id, type;
  final String? fromStatus, toStatus;
  final DateTime occurredAt;
}
