import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_payment_methods.dart';

final class BuyerPaymentMethodsRepositoryImpl
    implements BuyerPaymentMethodsRepository {
  BuyerPaymentMethodsRepositoryImpl(this._api);
  final NexaApiClient _api;
  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  void _requireId(String id) {
    if (!_uuid.hasMatch(id)) _invalid();
  }

  @override
  Future<List<BuyerPaymentMethod>> list() async {
    final data = (await _api.get(
      '/buyer/payment-methods',
      refreshAfterUnauthorized: true,
    )).data;
    if (data is! List) _invalid();
    final methods = data.map(_method).toList(growable: false);
    if (methods.map((m) => m.id).toSet().length != methods.length ||
        methods.where((m) => m.isDefault).length > 1) {
      _invalid();
    }
    return methods;
  }

  BuyerPaymentMethod _method(Object? value) {
    if (value is! Map) _invalid();
    final id = value['id'],
        brand = value['brand'],
        last4 = value['last4'],
        month = value['expiryMonth'],
        year = value['expiryYear'],
        isDefault = value['defaultMethod'];
    if (id is! String ||
        brand is! String ||
        last4 is! String ||
        !RegExp(r'^\d{4}$').hasMatch(last4) ||
        month is! int ||
        month < 1 ||
        month > 12 ||
        year is! int ||
        year < 2000 ||
        isDefault is! bool) {
      _invalid();
    }
    _requireId(id);
    return BuyerPaymentMethod(
      id: id,
      brand: brand,
      last4: last4,
      expiryMonth: month,
      expiryYear: year,
      isDefault: isDefault,
    );
  }

  @override
  Future<BuyerPaymentSetup> setup(String key) async {
    final body = (await _api.post(
      '/buyer/payment-methods/setup-intents',
      idempotencyKey: key,
    )).body;
    final id = body['setupIntentId'],
        secret = body['clientSecret'],
        status = body['status'],
        publicKey = body['publishableKey'];
    if (id is! String ||
        secret is! String ||
        status is! String ||
        publicKey is! String ||
        !id.startsWith('seti_') ||
        !secret.startsWith('${id}_secret_') ||
        !const {
          'requires_payment_method',
          'requires_confirmation',
          'requires_action',
          'processing',
          'succeeded',
          'canceled',
        }.contains(status.toLowerCase()) ||
        !RegExp(r'^pk_(test|live)_[A-Za-z0-9]{24,}$').hasMatch(publicKey)) {
      _invalid();
    }
    return BuyerPaymentSetup(
      id: id,
      secret: secret,
      status: status,
      publishableKey: publicKey,
    );
  }

  @override
  Future<void> makeDefault(String id) async {
    _requireId(id);
    _method(
      (await _api.request('PATCH', '/buyer/payment-methods/$id/default')).body,
    );
  }

  @override
  Future<void> remove(String id) async {
    _requireId(id);
    await _api.request('DELETE', '/buyer/payment-methods/$id');
  }

  Never _invalid() => throw const NexaApiFailure(
    code: 'invalid_payment_method_response',
    userMessage: 'No se pudieron confirmar los medios de pago.',
  );
}

final class SecureBuyerSetupKeyStore implements BuyerSetupKeyStore {
  SecureBuyerSetupKeyStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _storage;
  String _key(String scope) =>
      'buyer_setup_${sha256.convert(utf8.encode(scope))}';
  @override
  Future<String?> read(String scope) => _storage.read(key: _key(scope));
  @override
  Future<void> write(String scope, String key) =>
      _storage.write(key: _key(scope), value: key);
  @override
  Future<void> clear(String scope) => _storage.delete(key: _key(scope));
}
