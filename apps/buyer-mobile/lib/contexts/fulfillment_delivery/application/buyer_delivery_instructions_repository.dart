import '../domain/model/buyer_delivery_instruction.dart';
export '../domain/model/buyer_delivery_instruction.dart';

abstract interface class BuyerDeliveryInstructionsRepository {
  Future<BuyerDeliveryInstructions> load(String orderId);
  Future<BuyerDeliveryInstructions> publish(
    String orderId,
    BuyerDeliveryInstructionCommand command,
  );
}
