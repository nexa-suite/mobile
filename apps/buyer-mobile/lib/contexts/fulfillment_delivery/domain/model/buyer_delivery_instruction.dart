final class BuyerDeliveryInstruction {
  const BuyerDeliveryInstruction({
    required this.id,
    required this.version,
    required this.kind,
    required this.content,
    required this.recordedAt,
  });
  final String id, kind, content;
  final int version;
  final DateTime recordedAt;
}

final class BuyerDeliveryInstructions {
  BuyerDeliveryInstructions({
    required this.orderId,
    required this.version,
    required this.editable,
    required List<BuyerDeliveryInstruction> items,
  }) : items = List.unmodifiable(items);
  final String orderId;
  final int version;
  final bool editable;
  final List<BuyerDeliveryInstruction> items;
}

final class BuyerDeliveryInstructionCommand {
  const BuyerDeliveryInstructionCommand({
    required this.instructionId,
    required this.expectedVersion,
    required this.kind,
    required this.content,
    required this.idempotencyKey,
  });
  final String instructionId, kind, content, idempotencyKey;
  final int expectedVersion;
}
