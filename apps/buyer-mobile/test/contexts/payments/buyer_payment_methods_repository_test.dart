import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/payments/infrastructure/buyer_payment_methods_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _methodId = '00000000-0000-4000-8000-000000000001';
const _idempotencyKey = '00000000-0000-4000-8000-000000000002';
const _setupIntentId = 'seti_1AbCdEfGhIjKlMnOpQrStUvW';
const _publishableKey = 'pk_test_abcdefghijklmnopqrstuvwxyz123456';

void main() {
  test('lists only server-owned safe card summaries', () async {
    late http.Request request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode([
          {
            ..._method(defaultMethod: true),
            'providerPaymentMethodId': 'pm_provider_private',
            'clientSecret': 'seti_secret_private',
          },
        ]),
        200,
      );
    });

    final methods = await BuyerPaymentMethodsRepositoryImpl(api).list();

    expect(request.method, 'GET');
    expect(request.url.path, '/api/v1/buyer/payment-methods');
    expect(request.url.query, isEmpty);
    expect(request.headers['authorization'], 'Bearer access-secret');
    expect(methods, hasLength(1));
    expect(methods.single.id, _methodId);
    expect(methods.single.last4, '4242');
    expect(methods.single.isDefault, isTrue);
    api.close();
  });

  test('creates a SetupIntent with the stable idempotency key', () async {
    late http.Request request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode({
          'setupIntentId': _setupIntentId,
          'clientSecret': '${_setupIntentId}_secret_setup_only',
          'status': 'requires_payment_method',
          'publishableKey': _publishableKey,
        }),
        202,
      );
    });

    final setup = await BuyerPaymentMethodsRepositoryImpl(api)
        .setup(_idempotencyKey);

    expect(request.method, 'POST');
    expect(request.url.path, '/api/v1/buyer/payment-methods/setup-intents');
    expect(request.headers['idempotency-key'], _idempotencyKey);
    expect(request.body, isEmpty);
    expect(setup.id, _setupIntentId);
    expect(setup.secret, '${_setupIntentId}_secret_setup_only');
    expect(setup.publishableKey, _publishableKey);
    api.close();
  });

  test('sets default and deletes by API payment method UUID', () async {
    final requests = <http.Request>[];
    final api = _api((request) async {
      requests.add(request);
      if (request.method == 'PATCH') {
        return http.Response(jsonEncode(_method(defaultMethod: true)), 200);
      }
      return http.Response('', 204);
    });
    final repository = BuyerPaymentMethodsRepositoryImpl(api);

    await repository.makeDefault(_methodId);
    await repository.remove(_methodId);

    expect(requests.map((request) => request.method), ['PATCH', 'DELETE']);
    expect(requests.map((request) => request.url.path), [
      '/api/v1/buyer/payment-methods/$_methodId/default',
      '/api/v1/buyer/payment-methods/$_methodId',
    ]);
    api.close();
  });

  test('rejects duplicate defaults in a server list', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode([
          _method(defaultMethod: true),
          {
            ..._method(defaultMethod: true),
            'id': '00000000-0000-4000-8000-000000000003',
          },
        ]),
        200,
      );
    });

    await expectLater(
      BuyerPaymentMethodsRepositoryImpl(api).list(),
      throwsA(isA<NexaApiFailure>()),
    );
    api.close();
  });
}

Map<String, Object?> _method({required bool defaultMethod}) => {
  'id': _methodId,
  'type': 'CARD',
  'brand': 'visa',
  'last4': '4242',
  'expiryMonth': 12,
  'expiryYear': 2030,
  'defaultMethod': defaultMethod,
  'createdAt': '2026-11-16T12:00:00Z',
};

NexaApiClient _api(Future<http.Response> Function(http.Request) handler) =>
    NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(handler),
    )..sessionCredentials = _Credentials();

final class _Credentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'access-secret';

  @override
  int get authorityEpoch => 1;

  @override
  String get authorityFingerprint => 'buyer-scope';

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => null;
}
