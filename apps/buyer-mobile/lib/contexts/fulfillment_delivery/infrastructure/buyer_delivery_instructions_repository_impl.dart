import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_delivery_instructions_repository.dart';

final class BuyerDeliveryInstructionsRepositoryImpl
    implements BuyerDeliveryInstructionsRepository {
  BuyerDeliveryInstructionsRepositoryImpl(this._api);
  final NexaApiClient _api;
  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  String _path(String orderId) {
    if (!_uuid.hasMatch(orderId)) _invalid();
    return '/buyer/sales-orders/$orderId/customer-delivery-instructions';
  }

  @override
  Future<BuyerDeliveryInstructions> load(String orderId) async => _read(
    orderId,
    (await _api.get(_path(orderId), refreshAfterUnauthorized: true)).body,
  );
  @override
  Future<BuyerDeliveryInstructions> publish(
    String orderId,
    BuyerDeliveryInstructionCommand command,
  ) async {
    final result = _read(
      orderId,
      (await _api.request(
        'POST',
        _path(orderId),
        ifMatch: '"${command.expectedVersion}"',
        idempotencyKey: command.idempotencyKey,
        refreshAfterUnauthorized: true,
        body: {
          'instructionId': command.instructionId,
          'kind': command.kind,
          'content': command.content,
        },
      )).body,
    );
    if (result.version <= command.expectedVersion) _invalid();
    return result;
  }

  BuyerDeliveryInstructions _read(String orderId, Map<String, Object?> data) {
    if (data['salesOrderId'] != orderId ||
        data['version'] is! int ||
        (data['version'] as int) < 0 ||
        data['editable'] is! bool ||
        data['instructions'] is! List) {
      _invalid();
    }
    final items = (data['instructions'] as List).map((item) {
      if (item is! Map ||
          item['id'] is! String ||
          !_uuid.hasMatch(item['id'] as String) ||
          item['instructionVersion'] is! int ||
          (item['instructionVersion'] as int) < 1 ||
          item['kind'] is! String ||
          item['content'] is! String ||
          item['recordedAt'] is! String) {
        _invalid();
      }
      final time = DateTime.tryParse(item['recordedAt'] as String);
      if (time == null) _invalid();
      return BuyerDeliveryInstruction(
        id: item['id'] as String,
        version: item['instructionVersion'] as int,
        kind: item['kind'] as String,
        content: item['content'] as String,
        recordedAt: time,
      );
    }).toList();
    if (items.map((item) => item.id).toSet().length != items.length) _invalid();
    return BuyerDeliveryInstructions(
      orderId: orderId,
      version: data['version'] as int,
      editable: data['editable'] as bool,
      items: items,
    );
  }

  Never _invalid() => throw const NexaApiFailure(
    code: 'buyer_instructions_invalid',
    userMessage: 'No pudimos confirmar las indicaciones de entrega.',
  );
}
