import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_wallet_repository.dart';
import 'package:nexa_buyer_mobile/contexts/payments/infrastructure/buyer_wallet_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  test(
    'reads the current Buyer wallet without client-supplied account scope',
    () async {
      late http.Request captured;
      final api = _api((request) async {
        captured = request;
        return http.Response(jsonEncode(_activeWallet()), 200);
      });

      final wallet = await BuyerWalletRepositoryImpl(api)
          .readCurrentWallet(page: 0);

      expect(captured.method, 'GET');
      expect(captured.url.path, '/api/v1/buyer/wallet');
      expect(captured.url.queryParameters, {'page': '0', 'size': '25'});
      expect(
        captured.url.queryParameters.containsKey('clientAccountId'),
        isFalse,
      );
      expect(wallet.state, BuyerWalletState.active);
      expect(wallet.currency, 'PEN');
      expect(wallet.postedBalance, '120.50');
      expect(wallet.reservedBalance, '30.00');
      expect(wallet.availableBalance, '90.50');
      expect(wallet.movements.items.single.amountDelta, '-30.00');
      expect(wallet.movements.items.single.type, 'PURCHASE_RESERVED');
      expect(wallet.orderPaymentSupported, isFalse);
      api.close();
    },
  );

  test('reads server-reported order payment support capability', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({
          ..._activeWallet(),
          'capabilities': {'orderPaymentSupported': true},
        }),
        200,
      );
    });

    final wallet = await BuyerWalletRepositoryImpl(api)
        .readCurrentWallet(page: 0);

    expect(wallet.orderPaymentSupported, isTrue);
    api.close();
  });

  test('defaults omitted wallet capability to false', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({..._activeWallet(), 'capabilities': {}}),
        200,
      );
    });

    final wallet = await BuyerWalletRepositoryImpl(api)
        .readCurrentWallet(page: 0);

    expect(wallet.orderPaymentSupported, isFalse);
    api.close();
  });

  test('keeps uninitialized balances null instead of inventing zero', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({
          'status': 'NOT_INITIALIZED',
          'currency': 'PEN',
          'postedBalance': null,
          'reservedBalance': null,
          'availableBalance': null,
          'movements': {'items': [], 'page': 0, 'size': 25, 'total': 0},
        }),
        200,
      );
    });

    final wallet = await BuyerWalletRepositoryImpl(api)
        .readCurrentWallet(page: 0);

    expect(wallet.state, BuyerWalletState.notInitialized);
    expect(wallet.postedBalance, isNull);
    expect(wallet.reservedBalance, isNull);
    expect(wallet.availableBalance, isNull);
    api.close();
  });

  test('rejects synthetic balances for an uninitialized wallet', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({
          'status': 'NOT_INITIALIZED',
          'currency': 'PEN',
          'postedBalance': 0,
          'reservedBalance': null,
          'availableBalance': null,
          'movements': {'items': [], 'page': 0, 'size': 25, 'total': 0},
        }),
        200,
      );
    });

    await expectLater(
      BuyerWalletRepositoryImpl(api).readCurrentWallet(page: 0),
      throwsA(isA<NexaApiFailure>()),
    );
    api.close();
  });

  test('preserves the API 503 as an explicit unavailable response', () async {
    final api = _api(
      (_) async => http.Response('{"code":"WALLET_UNAVAILABLE"}', 503),
    );

    await expectLater(
      BuyerWalletRepositoryImpl(api).readCurrentWallet(page: 0),
      throwsA(
        isA<NexaApiFailure>().having(
          (failure) => failure.statusCode,
          'statusCode',
          503,
        ),
      ),
    );
    api.close();
  });
}

NexaApiClient _api(Future<http.Response> Function(http.Request) handler) =>
    NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(handler),
    )..sessionCredentials = _FakeCredentials();

Map<String, Object?> _activeWallet() => {
  'status': 'ACTIVE',
  'currency': 'PEN',
  'postedBalance': '120.50',
  'reservedBalance': '30.00',
  'availableBalance': '90.50',
  'movements': {
    'items': [
      {
        'type': 'PURCHASE_RESERVED',
        'amountDelta': '-30.00',
        'occurredAt': '2026-10-09T12:00:00Z',
        'source': 'unneeded-private-field',
      },
    ],
    'page': 0,
    'size': 25,
    'total': 1,
  },
};

final class _FakeCredentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'access-token';

  @override
  int get authorityEpoch => 1;

  @override
  String get authorityFingerprint => 'buyer-scope-v1';

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => 'next-token';
}
