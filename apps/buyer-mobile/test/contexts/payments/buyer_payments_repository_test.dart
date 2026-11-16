import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/payments/infrastructure/buyer_payments_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _receivableId = '00000000-0000-4000-8000-000000000001';
const _accountId = '00000000-0000-4000-8000-000000000002';
const _paymentId = '00000000-0000-4000-8000-000000000003';
const _idempotencyKey = '00000000-0000-4000-8000-000000000004';

void main() {
  test('uploads a scoped receivable proof with a stable command key', () async {
    late http.Request captured;
    final api = _api((request) async {
      captured = request;
      return http.Response(jsonEncode(_proof()), 201);
    });
    final result = await BuyerPaymentsRepositoryImpl(api).uploadTransferProof(
      receivableId: _receivableId,
      filename: 'transfer.pdf',
      contentType: 'application/pdf',
      bytes: const [37, 80, 68, 70],
      idempotencyKey: _idempotencyKey,
    );
    expect(captured.url.path, '/api/v1/business-document-evidence');
    expect(captured.headers['idempotency-key'], _idempotencyKey);
    expect(captured.body, contains('RECEIVABLE'));
    expect(captured.body, contains(_receivableId));
    expect(captured.body, contains('filename="transfer.pdf"'));
    expect(result.isAvailable, isFalse);
    api.close();
  });

  test('proof lookup rejects a response from another receivable', () async {
    final api = _api(
      (request) async => http.Response(
        jsonEncode({..._proof(), 'subjectId': _accountId}),
        200,
      ),
    );
    await expectLater(
      BuyerPaymentsRepositoryImpl(api).transferProof(
        receivableId: _receivableId,
        evidenceId: _proof()['id']! as String,
      ),
      throwsA(isA<NexaApiFailure>()),
    );
    api.close();
  });

  test('only sends a valid optional proof id in the payment command', () async {
    late http.Request captured;
    var calls = 0;
    final api = _api((request) async {
      calls++;
      captured = request;
      return http.Response(jsonEncode(_payment()), 201);
    });
    final repository = BuyerPaymentsRepositoryImpl(api);
    await repository.reportBankTransfer(
      receivableId: _receivableId,
      reference: 'BANK-REF-71',
      idempotencyKey: _idempotencyKey,
      proofEvidenceId: _proof()['id']! as String,
    );
    expect(jsonDecode(captured.body)['proofEvidenceId'], _proof()['id']);
    await expectLater(
      repository.reportBankTransfer(
        receivableId: _receivableId,
        reference: 'BANK-REF-71',
        idempotencyKey: _idempotencyKey,
        proofEvidenceId: '../bad',
      ),
      throwsA(isA<NexaApiFailure>()),
    );
    expect(calls, 1);
    api.close();
  });

  test(
    'rejects unsafe proof filenames and oversized files before dispatch',
    () async {
      var calls = 0;
      final api = _api((request) async {
        calls++;
        return http.Response('{}', 500);
      });
      final repository = BuyerPaymentsRepositoryImpl(api);
      for (final name in ['../transfer.pdf', 'transfer.exe', 'bad\n.pdf']) {
        await expectLater(
          repository.uploadTransferProof(
            receivableId: _receivableId,
            filename: name,
            contentType: 'application/pdf',
            bytes: const [1],
            idempotencyKey: _idempotencyKey,
          ),
          throwsA(isA<NexaApiFailure>()),
        );
      }
      await expectLater(
        repository.uploadTransferProof(
          receivableId: _receivableId,
          filename: 'transfer.pdf',
          contentType: 'application/pdf',
          bytes: List.filled(10 * 1024 * 1024 + 1, 0),
          idempotencyKey: _idempotencyKey,
        ),
        throwsA(isA<NexaApiFailure>()),
      );
      expect(calls, 0);
      api.close();
    },
  );

  test('reads payment history from the server-scoped receivable', () async {
    late http.Request captured;
    final api = _api((request) async {
      captured = request;
      return http.Response(
        jsonEncode({
          'items': [
            {
              ..._payment(),
              'clientAccountId': _accountId,
              'receivableNumber': 'REC-0001',
              'reference': 'BANK-REF-71',
              'reviewReason': null,
            },
          ],
          'page': 0,
          'size': 25,
          'total': 1,
        }),
        200,
      );
    });

    final page = await BuyerPaymentsRepositoryImpl(api).listForReceivable(
      receivableId: _receivableId,
      page: 0,
      expectedClientAccountId: _accountId,
    );

    expect(captured.method, 'GET');
    expect(captured.url.path, '/api/v1/receivables/$_receivableId/payments');
    expect(captured.url.queryParameters, {'page': '0', 'size': '25'});
    expect(
      captured.url.queryParameters.containsKey('clientAccountId'),
      isFalse,
    );
    expect(page.items.single.method, 'BANK_TRANSFER');
    expect(page.items.single.reference, 'BANK-REF-71');
    expect(page.items.single.status, 'PROCESSING');
    api.close();
  });

  test(
    'reports a transfer without an amount and with a stable UUID key',
    () async {
      late http.Request captured;
      final api = _api((request) async {
        captured = request;
        return http.Response(jsonEncode(_payment()), 201);
      });

      final payment = await BuyerPaymentsRepositoryImpl(api).reportBankTransfer(
        receivableId: _receivableId,
        reference: '  BANK-REF-71  ',
        idempotencyKey: _idempotencyKey,
      );

      expect(captured.method, 'POST');
      expect(captured.headers['x-nexa-client'], 'NATIVE');
      expect(captured.headers['x-nexa-surface'], 'PORTAL');
      expect(captured.headers['authorization'], 'Bearer access-secret');
      expect(
        captured.url.path,
        '/api/v1/receivables/$_receivableId/bank-transfer-payments',
      );
      expect(captured.headers['idempotency-key'], _idempotencyKey);
      expect(jsonDecode(captured.body), {
        'reference': 'BANK-REF-71',
        'proofEvidenceId': null,
      });
      expect(jsonDecode(captured.body), isNot(contains('amount')));
      expect(payment.status, 'PROCESSING');
      expect(payment.amount, '75.0');
      api.close();
    },
  );

  test('does not replay an ambiguous transfer command after a 401', () async {
    final credentials = _FakeCredentials();
    var requestCount = 0;
    final api = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((request) async {
        requestCount++;
        expect(request.headers['x-nexa-client'], 'NATIVE');
        expect(request.headers['x-nexa-surface'], 'PORTAL');
        return http.Response('{"code":"EXPIRED"}', 401);
      }),
    )..sessionCredentials = credentials;

    await expectLater(
      BuyerPaymentsRepositoryImpl(api).reportBankTransfer(
        receivableId: _receivableId,
        reference: 'BANK-REF-71',
        idempotencyKey: _idempotencyKey,
      ),
      throwsA(isA<NexaApiFailure>()),
    );

    expect(requestCount, 1);
    expect(credentials.refreshCalls, 0);
    api.close();
  });

  test('rejects payment history from another receivable account', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({
          'items': [
            {
              ..._payment(),
              'clientAccountId': '00000000-0000-4000-8000-000000000099',
              'reference': null,
              'reviewReason': null,
            },
          ],
          'page': 0,
          'size': 25,
          'total': 1,
        }),
        200,
      );
    });

    await expectLater(
      BuyerPaymentsRepositoryImpl(api).listForReceivable(
        receivableId: _receivableId,
        page: 0,
        expectedClientAccountId: _accountId,
      ),
      throwsA(isA<NexaApiFailure>()),
    );
    api.close();
  });
}

Map<String, Object?> _proof() => {
  'id': '00000000-0000-4000-8000-000000000005',
  'subjectType': 'RECEIVABLE',
  'subjectId': _receivableId,
  'originalFilename': 'transfer.pdf',
  'lifecycleStatus': 'SCANNING',
};

NexaApiClient _api(Future<http.Response> Function(http.Request) handler) =>
    NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(handler),
    )..sessionCredentials = _FakeCredentials();

Map<String, Object?> _payment() => {
  'id': _paymentId,
  'receivableId': _receivableId,
  'method': 'BANK_TRANSFER',
  'status': 'PROCESSING',
  'amount': 75.0,
  'currency': 'PEN',
  'createdAt': '2026-10-09T12:00:00Z',
  'completedAt': null,
};

final class _FakeCredentials implements SessionCredentialProvider {
  int refreshCalls = 0;

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
  ) async {
    refreshCalls++;
    return 'new-access';
  }
}
