import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_handoff_receipt_repository.dart';

final class BuyerHandoffReceiptRepositoryImpl
    implements BuyerHandoffReceiptRepository {
  BuyerHandoffReceiptRepositoryImpl(this._api);
  final NexaApiClient _api;

  @override
  Future<BuyerHandoffReceipt> accept({
    required String token,
    required String expectedDeliveryId,
    required String idempotencyKey,
  }) async {
    final response = await _api.request(
      'POST',
      '/delivery-handoff/receipts',
      body: {'token': token, 'expectedDeliveryId': expectedDeliveryId},
      idempotencyKey: idempotencyKey,
      refreshAfterUnauthorized: true,
    );
    final data = response.body;
    String field(String name) {
      final value = data[name];
      if (value is! String || value.trim().isEmpty) _invalid();
      return value;
    }

    if (field('deliveryId') != expectedDeliveryId ||
        data['orderCompleted'] is! bool ||
        data['replayed'] is! bool) {
      _invalid();
    }
    return BuyerHandoffReceipt(
      receiptId: field('receiptId'),
      deliveryId: field('deliveryId'),
      podId: field('podId'),
      deliveryStatus: field('deliveryStatus'),
      podStatus: field('podStatus'),
      salesOrderStatus: field('salesOrderStatus'),
      orderCompleted: data['orderCompleted'] as bool,
      replayed: data['replayed'] as bool,
    );
  }

  Never _invalid() => throw const NexaApiFailure(
    code: 'buyer_handoff_response_invalid',
    userMessage:
        'No pudimos confirmar el resultado. Reintenta con el mismo código.',
    retryable: true,
  );
}
