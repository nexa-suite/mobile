import '../domain/model/buyer_handoff_receipt.dart';
export '../domain/model/buyer_handoff_receipt.dart';

abstract interface class BuyerHandoffReceiptRepository {
  Future<BuyerHandoffReceipt> accept({
    required String token,
    required String expectedDeliveryId,
    required String idempotencyKey,
  });
}
