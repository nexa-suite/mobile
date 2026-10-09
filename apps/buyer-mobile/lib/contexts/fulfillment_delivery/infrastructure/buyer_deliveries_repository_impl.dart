import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

import '../application/buyer_deliveries_repository.dart';

final class BuyerDeliveriesRepositoryImpl implements BuyerDeliveriesRepository {
  BuyerDeliveriesRepositoryImpl(this._api);

  static const pageSize = 25;

  final NexaApiClient _api;

  @override
  Future<BuyerDeliveryPageProjection> list({required int page}) async {
    if (page < 0) _invalidResponse();
    final response = await _api.get(
      '/buyer/deliveries',
      query: {'page': '$page', 'size': '$pageSize'},
      refreshAfterUnauthorized: true,
    );
    final rawItems = response.body['items'];
    final responsePage = _int(response.body['page']);
    final size = _int(response.body['size']);
    final total = _int(response.body['total']);
    if (rawItems is! List ||
        responsePage != page ||
        size != pageSize ||
        total == null ||
        total < 0) {
      _invalidResponse();
    }
    final items = rawItems.map(_parseDelivery).toList(growable: false);
    if (items.length > pageSize || total < items.length) _invalidResponse();
    if (items.map((item) => item.id).toSet().length != items.length) {
      _invalidResponse();
    }
    return BuyerDeliveryPageProjection(
      items: items,
      page: responsePage!,
      size: size!,
      total: total,
    );
  }

  @override
  Future<BuyerDeliveryDetailProjection> detail(String deliveryId) async {
    if (!_isUuid(deliveryId)) _invalidResponse();
    final encodedId = Uri.encodeComponent(deliveryId);
    final responses = await Future.wait([
      _api.get('/buyer/deliveries/$encodedId', refreshAfterUnauthorized: true),
      _api.get(
        '/buyer/deliveries/$encodedId/events',
        refreshAfterUnauthorized: true,
      ),
    ]);
    final delivery = _parseDelivery(responses[0].body);
    final rawEvents = responses[1].data;
    if (delivery.id != deliveryId || rawEvents is! List) _invalidResponse();
    final events = rawEvents.map(_parseEvent).toList(growable: false);
    return BuyerDeliveryDetailProjection(delivery: delivery, events: events);
  }

  BuyerDeliveryProjection _parseDelivery(Object? value) {
    final delivery = _map(value);
    if (delivery == null) _invalidResponse();
    final id = _string(delivery['id']);
    final salesOrderNumber = _string(delivery['salesOrderNumber']);
    final status = _string(delivery['status']);
    final version = _int(delivery['version']);
    final createdAt = _dateTime(delivery['createdAt']);
    final updatedAt = _dateTime(delivery['updatedAt']);
    if (id == null ||
        !_isUuid(id) ||
        salesOrderNumber == null ||
        status == null ||
        version == null ||
        version < 0 ||
        createdAt == null ||
        updatedAt == null) {
      _invalidResponse();
    }
    return BuyerDeliveryProjection(
      id: id,
      salesOrderNumber: salesOrderNumber,
      status: status,
      version: version,
      createdAt: createdAt,
      updatedAt: updatedAt,
      destination: _optionalString(delivery, 'destination'),
      scheduledAt: _optionalDateTime(delivery, 'scheduledAt'),
      dispatchedAt: _optionalDateTime(delivery, 'dispatchedAt'),
      deliveredAt: _optionalDateTime(delivery, 'deliveredAt'),
      proofOfDeliveryStatus: _optionalString(delivery, 'proofOfDeliveryStatus'),
    );
  }

  BuyerDeliveryEventProjection _parseEvent(Object? value) {
    final event = _map(value);
    if (event == null) _invalidResponse();
    final type = _string(event['type']);
    final occurredAt = _dateTime(event['occurredAt']);
    if (type == null || occurredAt == null) {
      _invalidResponse();
    }
    return BuyerDeliveryEventProjection(type: type, occurredAt: occurredAt);
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.trim().isNotEmpty ? value : null;

  String? _optionalString(Map<String, Object?> source, String key) {
    final value = source[key];
    if (value == null) return null;
    final parsed = _string(value);
    if (parsed == null) _invalidResponse();
    return parsed;
  }

  int? _int(Object? value) => value is int ? value : null;

  DateTime? _dateTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;

  DateTime? _optionalDateTime(Map<String, Object?> source, String key) {
    final value = source[key];
    if (value == null) return null;
    final parsed = _dateTime(value);
    if (parsed == null) _invalidResponse();
    return parsed;
  }

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  ).hasMatch(value);

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_buyer_delivery_response',
    userMessage: 'The delivery information could not be read.',
  );
}
