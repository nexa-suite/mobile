/// The server's receipt/POD result; neither a scan nor token validation is delivery.
final class BuyerHandoffReceipt {
  const BuyerHandoffReceipt({
    required this.receiptId,
    required this.deliveryId,
    required this.podId,
    required this.deliveryStatus,
    required this.podStatus,
    required this.salesOrderStatus,
    required this.orderCompleted,
    required this.replayed,
  });
  final String receiptId;
  final String deliveryId;
  final String podId;
  final String deliveryStatus;
  final String podStatus;
  final String salesOrderStatus;
  final bool orderCompleted;
  final bool replayed;
}
