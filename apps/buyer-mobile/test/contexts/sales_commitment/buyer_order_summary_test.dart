import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/infrastructure/buyer_orders_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  for (final format in ['pdf', 'csv']) {
    test('downloads scoped $format order summary', () async {
      late http.Request request;
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((value) async {
          request = value;
          return http.Response(
            'summary',
            200,
            headers: {
              'content-type': format == 'pdf'
                  ? 'application/pdf'
                  : 'text/csv; charset=utf-8',
            },
          );
        }),
      )..sessionCredentials = _Credentials();
      final response = await BuyerOrdersRepositoryImpl(api)
          .downloadSummary('order-1', format);
      expect(
        request.url.path,
        '/api/v1/buyer/orders/order-1/exports/summary.$format',
      );
      expect(request.headers['authorization'], 'Bearer token');
      expect(response.bytes, isNotEmpty);
      response.clear();
      expect(response.bytes.every((byte) => byte == 0), isTrue);
      api.close();
    });
  }

  test('rejects an unexpected content type', () async {
    final api = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(
        (_) async =>
            http.Response('bad', 200, headers: {'content-type': 'text/html'}),
      ),
    )..sessionCredentials = _Credentials();
    await expectLater(
      BuyerOrdersRepositoryImpl(api).downloadSummary('order-1', 'pdf'),
      throwsA(isA<NexaApiFailure>()),
    );
    api.close();
  });
}

final class _Credentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'token';
  @override
  int get authorityEpoch => 1;
  @override
  String get authorityFingerprint => '1';
  @override
  Future<String?> refreshIfCurrent(String tokenUsed, int epoch) async => null;
}
