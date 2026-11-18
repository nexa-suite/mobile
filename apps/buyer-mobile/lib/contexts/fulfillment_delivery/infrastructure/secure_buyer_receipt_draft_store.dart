import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../application/buyer_receipt_draft_store.dart';

abstract interface class BuyerReceiptDraftSecureStorage {
  Future<String?> read({required String key});
  Future<void> write({required String key, required String value});
  Future<void> delete({required String key});
}

final class FlutterBuyerReceiptDraftSecureStorage
    implements BuyerReceiptDraftSecureStorage {
  FlutterBuyerReceiptDraftSecureStorage([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read({required String key}) => _storage.read(key: key);

  @override
  Future<void> write({required String key, required String value}) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete({required String key}) => _storage.delete(key: key);
}

final class SecureBuyerReceiptDraftStore implements BuyerReceiptDraftStore {
  SecureBuyerReceiptDraftStore({BuyerReceiptDraftSecureStorage? secureStorage})
    : _secureStorage = secureStorage ?? FlutterBuyerReceiptDraftSecureStorage();

  final BuyerReceiptDraftSecureStorage _secureStorage;

  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );
  static final _quantity = RegExp(r'^[0-9eE+.-]{0,32}$');

  @override
  Future<BuyerReceiptDraft?> read({
    required String scopeKey,
    required String deliveryId,
  }) async {
    final key = _key(scopeKey, deliveryId);
    final encoded = await _secureStorage.read(key: key);
    if (encoded == null) return null;

    try {
      final value = jsonDecode(encoded);
      if (value is! Map<String, dynamic> || value['schemaVersion'] != 1) {
        _invalid();
      }
      final storedDeliveryId = value['deliveryId'];
      final attemptId = value['attemptId'];
      final decision = value['decision'];
      final quantity = value['acceptedQuantity'];
      final reason = value['reason'];
      if (storedDeliveryId != deliveryId ||
          attemptId is! String ||
          decision is! String ||
          quantity is! String ||
          reason is! String) {
        _invalid();
      }
      final draft = BuyerReceiptDraft(
        deliveryId: storedDeliveryId as String,
        attemptId: attemptId,
        decision: decision,
        acceptedQuantity: quantity,
        reason: reason,
      );
      _validate(draft);
      return draft;
    } on FormatException {
      rethrow;
    } catch (_) {
      _invalid();
    }
  }

  @override
  Future<void> write({
    required String scopeKey,
    required BuyerReceiptDraft draft,
  }) async {
    final key = _key(scopeKey, draft.deliveryId);
    _validate(draft);
    await _secureStorage.write(
      key: key,
      value: jsonEncode({
        'schemaVersion': 1,
        'deliveryId': draft.deliveryId,
        'attemptId': draft.attemptId,
        'decision': draft.decision,
        'acceptedQuantity': draft.acceptedQuantity,
        'reason': draft.reason,
      }),
    );
  }

  @override
  Future<void> delete({required String scopeKey, required String deliveryId}) =>
      _secureStorage.delete(key: _key(scopeKey, deliveryId));

  String _key(String scopeKey, String deliveryId) {
    if (scopeKey.trim().isEmpty ||
        scopeKey.length > 1024 ||
        !_uuid.hasMatch(deliveryId)) {
      _invalid();
    }
    final identity = sha256.convert(utf8.encode('$scopeKey|$deliveryId'));
    return 'buyer_receipt_draft_v1_$identity';
  }

  void _validate(BuyerReceiptDraft draft) {
    if (!_uuid.hasMatch(draft.deliveryId) ||
        !_uuid.hasMatch(draft.attemptId) ||
        !const {'ACCEPTED', 'DISPUTED'}.contains(draft.decision) ||
        draft.acceptedQuantity.length > 32 ||
        !_quantity.hasMatch(draft.acceptedQuantity) ||
        draft.reason.length > 2000) {
      _invalid();
    }
  }

  Never _invalid() => throw const FormatException(
    'Stored Buyer receipt draft cannot be read safely.',
  );
}
