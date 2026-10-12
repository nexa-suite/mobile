import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_orders_repository.dart';

final class BuyerOrdersRepositoryImpl implements BuyerOrdersRepository {
  BuyerOrdersRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<BuyerOrdersPageProjection> list({required int page}) async {
    final response = await _api.get(
      '/sales-orders',
      query: {'page': '$page', 'size': '25', 'sort': 'createdAt,desc'},
      refreshAfterUnauthorized: true,
    );
    final values = response.body['items'];
    if (values is! List) _invalidResponse();
    return BuyerOrdersPageProjection(
      items: values.map(_parseOrder).toList(growable: false),
      page: _int(response.body['page']) ?? page,
      size: _int(response.body['size']) ?? 25,
      total: _int(response.body['total']) ?? 0,
    );
  }

  @override
  Future<BuyerOrderProjection> detail(String orderId) async {
    if (!RegExp(r'^[A-Za-z0-9-]{1,80}$').hasMatch(orderId)) {
      _invalidResponse();
    }
    final response = await _api.get(
      '/sales-orders/$orderId',
      refreshAfterUnauthorized: true,
    );
    return _parseOrder(response.body);
  }

  BuyerOrderProjection _parseOrder(Object? value) {
    final order = _map(value);
    if (order == null) _invalidResponse();
    final id = _string(order['id']);
    final number = _string(order['number']);
    final status = _string(order['status']);
    final lines = order['lines'];
    if (id == null || number == null || status == null || lines is! List) {
      _invalidResponse();
    }
    return BuyerOrderProjection(
      id: id,
      number: number,
      status: status,
      version: _int(order['version']) ?? 0,
      createdAt: _string(order['createdAt']),
      requestedDeliveryDate: _string(order['requestedDeliveryDate']),
      total: _num(order['total']),
      currency: _string(order['currency']),
      lines: lines.map(_parseLine).toList(growable: false),
    );
  }

  BuyerOrderLineProjection _parseLine(Object? value) {
    final line = _map(value);
    if (line == null) _invalidResponse();
    final name = _string(line['itemName']);
    final quantity = _num(line['quantity']);
    final unit = _string(line['unit']);
    if (name == null || quantity == null || unit == null) _invalidResponse();
    return BuyerOrderLineProjection(
      itemName: name,
      quantity: quantity,
      unit: unit,
      presentation: _string(line['presentation']),
      unitPriceAmount: _num(line['unitPriceAmount']),
      unitPriceCurrency: _string(line['unitPriceCurrency']),
      lineSubtotal: _num(line['lineSubtotal']),
    );
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  int? _int(Object? value) => value is int ? value : null;

  num? _num(Object? value) => value is num ? value : null;

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_order_response',
    userMessage: 'The order response could not be read.',
  );
}
