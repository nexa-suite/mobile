import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/credit_receivables/infrastructure/buyer_credit_exposure_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _receivableId = '00000000-0000-4000-8000-000000000001';
const _accountId = '00000000-0000-4000-8000-000000000002';

void main() {
  test('lists server-scoped receivables with bounded pagination', () async {
    late http.Request captured;
    final api = _api((request) async {
      captured = request;
      return http.Response(
        jsonEncode({
          'items': [_receivable()],
          'page': 1,
          'size': 25,
          'total': 26,
        }),
        200,
      );
    });

    final page = await BuyerCreditExposureRepositoryImpl(api)
        .listBuyerReceivables(page: 1);

    expect(captured.method, 'GET');
    expect(captured.url.path, '/api/v1/receivables');
    expect(captured.url.queryParameters, {'page': '1', 'size': '25'});
    expect(
      captured.url.queryParameters.containsKey('clientAccountId'),
      isFalse,
    );
    expect(captured.headers['x-nexa-client'], 'NATIVE');
    expect(captured.headers['x-nexa-surface'], 'PORTAL');
    expect(page.page, 1);
    expect(page.size, 25);
    expect(page.total, 26);
    expect(page.items.single.remaining, '75.0');
    expect(page.items.single.currency, 'PEN');
    expect(page.items.single.dueAt, DateTime.utc(2026, 10, 13));
    api.close();
  });

  test('rejects receivables outside the server-selected account', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({
          'items': [
            {..._receivable(), 'clientAccountId': 'not-a-uuid'},
          ],
          'page': 0,
          'size': 25,
          'total': 1,
        }),
        200,
      );
    });

    await expectLater(
      BuyerCreditExposureRepositoryImpl(api).listBuyerReceivables(page: 0),
      throwsA(isA<NexaApiFailure>()),
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

Map<String, Object?> _receivable() => {
  'id': _receivableId,
  'clientAccountId': _accountId,
  'subjectType': 'SALES_ORDER',
  'subjectId': '00000000-0000-4000-8000-000000000003',
  'number': 'REC-0001',
  'currency': 'PEN',
  'amount': 100.0,
  'amountPaid': 25.0,
  'remaining': 75.0,
  'status': 'OPEN',
  'dueAt': '2026-10-13T00:00:00Z',
  'version': 2,
};

final class _FakeCredentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'access-secret';

  @override
  int get authorityEpoch => 1;

  @override
  String get authorityFingerprint => 'buyer-scope-v1';

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => null;
}
