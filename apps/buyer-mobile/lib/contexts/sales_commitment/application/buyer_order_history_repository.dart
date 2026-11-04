import '../domain/model/buyer_order_event.dart';
export '../domain/model/buyer_order_event.dart';

abstract interface class BuyerOrderHistoryRepository {
  Future<List<BuyerOrderEvent>> history(String orderId);
}
