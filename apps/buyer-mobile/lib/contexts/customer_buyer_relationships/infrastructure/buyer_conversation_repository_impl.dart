import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_conversation_repository.dart';

final class BuyerConversationRepositoryImpl
    implements BuyerConversationRepository {
  BuyerConversationRepositoryImpl(this._api);
  final NexaApiClient _api;
  @override
  Future<BuyerMessagePage> list(String? cursor) async {
    final data = (await _api.get(
      '/buyer/messages',
      query: {'size': '50', 'afterMessageId': ?cursor},
      refreshAfterUnauthorized: true,
    )).body;
    final items = data['items'];
    final next = data['nextAfterMessageId'];
    final more = data['hasMore'];
    if (items is! List || more is! bool || (next != null && next is! String)) {
      _invalid();
    }
    final messages = items.map(_message).toList(growable: false);
    if (messages.map((m) => m.id).toSet().length != messages.length ||
        (more && (messages.isEmpty || next == null || next == cursor))) {
      _invalid();
    }
    return BuyerMessagePage(messages, next as String?, more);
  }

  @override
  Future<BuyerMessage> send(String body, String key) async => _message(
    (await _api.post(
      '/buyer/messages',
      body: {'body': body},
      idempotencyKey: key,
    )).body,
  );
  BuyerMessage _message(Object? raw) {
    if (raw is! Map) _invalid();
    final id = raw['id'],
        body = raw['body'],
        kind = raw['senderKind'],
        created = raw['createdAt'];
    if (id is! String ||
        id.isEmpty ||
        body is! String ||
        body.isEmpty ||
        (kind != 'BUYER' && kind != 'SALES') ||
        created is! String) {
      _invalid();
    }
    final time = DateTime.tryParse(created);
    if (time == null) _invalid();
    return BuyerMessage(
      id: id,
      body: body,
      senderKind: kind as String,
      createdAt: time,
    );
  }

  Never _invalid() => throw const NexaApiFailure(
    code: 'invalid_message_response',
    userMessage: 'No se pudo confirmar la conversación.',
  );
}

final class SecurePendingBuyerMessageStore implements PendingBuyerMessageStore {
  SecurePendingBuyerMessageStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _storage;
  String _key(String scope) =>
      'buyer_message_${sha256.convert(utf8.encode(scope))}';
  @override
  Future<PendingBuyerMessage?> read(String scope) async {
    final value = await _storage.read(key: _key(scope));
    if (value == null) return null;
    final data = jsonDecode(value);
    if (data is! Map ||
        data['body'] is! String ||
        data['key'] is! String ||
        (data['body'] as String).trim().isEmpty ||
        (data['key'] as String).isEmpty) {
      throw const FormatException('Invalid pending message');
    }
    return PendingBuyerMessage(data['body'] as String, data['key'] as String);
  }

  @override
  Future<void> write(String scope, PendingBuyerMessage message) =>
      _storage.write(
        key: _key(scope),
        value: jsonEncode({'body': message.body, 'key': message.key}),
      );
  @override
  Future<void> clear(String scope) => _storage.delete(key: _key(scope));
}
