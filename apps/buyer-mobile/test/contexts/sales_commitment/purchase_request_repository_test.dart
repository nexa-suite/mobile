import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/purchase_request_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/application/purchase_request_repository.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/infrastructure/purchase_request_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _accountId = '00000000-0000-4000-8000-000000000002';
const _membershipId = '00000000-0000-4000-8000-000000000003';
const _addressId = '00000000-0000-4000-8000-000000000004';
const _draftId = '00000000-0000-4000-8000-000000000005';
const _skuId = '00000000-0000-4000-8000-000000000006';

void main() {
  test(
    'lists only server-scoped draft summaries with visible pagination',
    () async {
      late http.Request captured;
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          captured = request;
          return http.Response(
            jsonEncode({
              'items': [
                {
                  'id': _draftId,
                  'status': 'DRAFT',
                  'version': 3,
                  'requestedDeliveryDate': '2026-10-13',
                  'lineCount': 1,
                  'createdAt': '2026-10-09T12:00:00Z',
                  'updatedAt': '2026-10-09T13:00:00Z',
                },
              ],
              'page': 1,
              'size': 20,
              'totalItems': 21,
              'totalPages': 2,
            }),
            200,
          );
        }),
      )..sessionCredentials = _FakeCredentials();
      final repository = PurchaseRequestRepositoryImpl(
        api,
        _RecordingKeyStore(),
      );

      final result = await repository.listDrafts(page: 1);

      expect(captured.url.path, '/api/v1/buyer/purchase-request-drafts');
      expect(captured.url.queryParameters, {'page': '1', 'size': '20'});
      expect(
        captured.url.queryParameters.containsKey('clientAccountId'),
        isFalse,
      );
      expect(result.page, 1);
      expect(result.totalPages, 2);
      expect(result.items.single.version, 3);
      expect(result.items.single.lineCount, 1);
      expect(result.items.single.status, 'DRAFT');
      api.close();
    },
  );

  test('uses server-resolved Buyer account, UUID SKU, ETags, and persisted submit key', () async {
    final requests = <http.Request>[];
    final keyStore = _RecordingKeyStore();
    final api = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((request) async {
        requests.add(request);
        final path = request.url.path;
        if (path.endsWith('/client-accounts/me')) {
          return http.Response(
            jsonEncode({
              'id': _accountId,
              'buyerMembershipId': _membershipId,
              'businessName': 'Cuenta Buyer',
              'paymentCondition': 'BANK_TRANSFER',
            }),
            200,
          );
        }
        if (path.endsWith('/client-accounts/$_accountId/addresses')) {
          return http.Response(
            jsonEncode([
              {
                'id': _addressId,
                'clientAccountId': _accountId,
                'label': 'Principal',
                'line': 'Av. Central 100',
                'active': true,
                'defaultAddress': true,
                'latitude': -12.1,
                'longitude': -77.1,
              },
            ]),
            200,
          );
        }
        if (path.endsWith('/buyer/purchase-request-drafts') &&
            request.method == 'POST') {
          expect(jsonDecode(request.body), {
            'clientAccountId': _accountId,
            'requestedDeliveryDate': '2026-10-13',
          });
          expect(request.headers.containsKey('idempotency-key'), isFalse);
          return _draftResponse(version: 0, etag: '"0"');
        }
        if (path.endsWith('/$_draftId/lines')) {
          expect(request.method, 'PUT');
          expect(request.headers['if-match'], '"0"');
          expect(jsonDecode(request.body), {
            'lines': [
              {'skuId': _skuId, 'quantity': 2, 'unit': 'BOX', 'notes': ''},
            ],
          });
          return _draftResponse(version: 1, etag: '"1"', lines: [_line()]);
        }
        if (path.endsWith('/$_draftId/destination')) {
          expect(request.headers['if-match'], '"1"');
          expect(jsonDecode(request.body), {'addressId': _addressId});
          return _draftResponse(
            version: 2,
            etag: '"2"',
            lines: [_line()],
            destination: {'addressId': _addressId},
          );
        }
        if (path.endsWith('/$_draftId/route-previews')) {
          expect(request.method, 'POST');
          expect(request.headers['if-match'], '"2"');
          expect(jsonDecode(request.body), {'provider': 'LOCAL_ESTIMATE'});
          return _draftResponse(
            version: 3,
            etag: '"3"',
            lines: [_line()],
            destination: {'addressId': _addressId},
            route: {'provider': 'LOCAL_ESTIMATE', 'estimated': true},
            warehouseSelection: {'warehouseId': _skuId},
          );
        }
        if (path.endsWith('/$_draftId/preferences')) {
          expect(request.headers['if-match'], '"3"');
          expect(jsonDecode(request.body), {
            'paymentPreference': 'BANK_TRANSFER',
            'requestedDeliveryDate': '2026-10-13',
          });
          return _draftResponse(
            version: 4,
            etag: '"4"',
            lines: [_line()],
            destination: {'addressId': _addressId},
            route: {'provider': 'LOCAL_ESTIMATE', 'estimated': true},
            warehouseSelection: {'warehouseId': _skuId},
            paymentPreference: 'BANK_TRANSFER',
            requestedDeliveryDate: '2026-10-13',
          );
        }
        if (path.endsWith('/$_draftId/review')) {
          return http.Response(
            jsonEncode({
              'readyToSubmit': true,
              'missing': [],
              'draft': _draft(
                version: 4,
                lines: [_line()],
                destination: {'addressId': _addressId},
                route: {'provider': 'LOCAL_ESTIMATE', 'estimated': true},
                warehouseSelection: {'warehouseId': _skuId},
                paymentPreference: 'BANK_TRANSFER',
                requestedDeliveryDate: '2026-10-13',
              ),
            }),
            200,
          );
        }
        if (path.endsWith('/$_draftId/submissions')) {
          expect(request.method, 'POST');
          expect(request.headers['if-match'], '"4"');
          expect(
            request.headers['idempotency-key'],
            '00000000-0000-4000-8000-000000000008',
          );
          expect(request.body, isEmpty);
          return _draftResponse(
            version: 5,
            etag: '"5"',
            status: 'SUBMITTED',
            lines: [_line()],
            destination: {'addressId': _addressId},
            route: {'provider': 'LOCAL_ESTIMATE', 'estimated': true},
            warehouseSelection: {'warehouseId': _skuId},
            paymentPreference: 'BANK_TRANSFER',
            requestedDeliveryDate: '2026-10-13',
            submittedAt: '2026-10-09T15:00:00Z',
          );
        }
        fail('Unexpected request: ${request.method} ${request.url.path}');
      }),
    )..sessionCredentials = _FakeCredentials();
    final repository = PurchaseRequestRepositoryImpl(api, keyStore);

    final context = await repository.loadBuyerPurchaseContext();
    expect(context.clientAccountId, _accountId);
    expect(context.buyerMembershipId, _membershipId);
    expect(context.addresses.single.defaultAddress, isTrue);
    expect(requests[0].url.path, '/api/v1/client-accounts/me');
    expect(
      requests[1].url.path,
      '/api/v1/client-accounts/$_accountId/addresses',
    );
    expect(
      requests.every(
        (request) =>
            !request.url.queryParameters.containsKey('clientAccountId'),
      ),
      isTrue,
    );

    var draft = await repository.createDraft(
      clientAccountId: context.clientAccountId,
      requestedDeliveryDate: '2026-10-13',
    );
    draft = await repository.replaceLine(
      draft: draft,
      sellableSkuId: _skuId,
      quantity: 2,
      unit: 'BOX',
    );
    draft = await repository.setDestination(
      draft: draft,
      addressId: _addressId,
    );
    draft = await repository.previewRoute(draft);
    draft = await repository.setPreferences(
      draft: draft,
      paymentPreference: 'BANK_TRANSFER',
      requestedDeliveryDate: '2026-10-13',
    );
    final review = await repository.review(draft.id);
    expect(review.readyToSubmit, isTrue);
    final submitted = await repository.submit(
      review.draft,
      scopeKey: 'membership-1|tenant-1|workspace-1',
    );

    expect(submitted.status, 'SUBMITTED');
    expect(submitted.version, 5);
    expect(keyStore.scopeKey, 'membership-1|tenant-1|workspace-1');
    expect(keyStore.draftId, _draftId);
    expect(keyStore.version, 4);
    expect(keyStore.completed, isTrue);
    expect(requests[2].headers['x-nexa-client'], 'NATIVE');
    expect(requests[2].headers['x-nexa-surface'], 'PORTAL');
    api.close();
  });

  test('never sends a catalog code as the draft SKU', () async {
    var requestCount = 0;
    final api = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((_) async {
        requestCount++;
        return http.Response('{}', 200);
      }),
    )..sessionCredentials = _FakeCredentials();
    final repository = PurchaseRequestRepositoryImpl(api, _RecordingKeyStore());
    const draft = PurchaseRequestDraftProjection(
      id: _draftId,
      clientAccountId: _accountId,
      status: 'DRAFT',
      version: 0,
      etag: '"0"',
      lines: [],
    );

    await expectLater(
      repository.replaceLine(
        draft: draft,
        sellableSkuId: 'CAT-0001',
        quantity: 1,
        unit: 'UNIT',
      ),
      throwsA(isA<NexaApiFailure>()),
    );
    expect(requestCount, 0);
    api.close();
  });

  test('sends WALLET as consent without client beneficiary data', () async {
    late http.Request captured;
    var requestCount = 0;
    final api = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((request) async {
        requestCount++;
        captured = request;
        return _draftResponse(
          version: 1,
          etag: '"1"',
          paymentPreference: 'WALLET',
          requestedDeliveryDate: '2026-10-13',
        );
      }),
    )..sessionCredentials = _FakeCredentials();
    final repository = PurchaseRequestRepositoryImpl(api, _RecordingKeyStore());
    const draft = PurchaseRequestDraftProjection(
      id: _draftId,
      clientAccountId: _accountId,
      status: 'DRAFT',
      version: 0,
      etag: '"0"',
      lines: [],
    );

    final updated = await repository.setPreferences(
      draft: draft,
      paymentPreference: 'WALLET',
      requestedDeliveryDate: '2026-10-13',
    );

    expect(captured.method, 'PUT');
    expect(
      captured.url.path,
      '/api/v1/buyer/purchase-request-drafts/$_draftId/preferences',
    );
    expect(captured.headers['if-match'], '"0"');
    expect(jsonDecode(captured.body), {
      'paymentPreference': 'WALLET',
      'requestedDeliveryDate': '2026-10-13',
    });
    expect(updated.paymentPreference, 'WALLET');
    await expectLater(
      repository.setPreferences(
        draft: updated,
        paymentPreference: 'WALLET',
        requestedDeliveryDate: '2026-10-13T00:00:00Z',
      ),
      throwsA(isA<NexaApiFailure>()),
    );
    expect(requestCount, 1);
    api.close();
  });

  test(
    'ambiguous submit retry reuses the persisted key and exact draft version',
    () async {
      final keys = <String?>[];
      var requestCount = 0;
      final keyStore = _RecordingKeyStore();
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          requestCount++;
          keys.add(request.headers['idempotency-key']);
          expect(request.headers['if-match'], '"4"');
          if (requestCount == 1) {
            throw http.ClientException('connection dropped after request');
          }
          return _draftResponse(
            version: 5,
            etag: '"5"',
            status: 'SUBMITTED',
            lines: [_line()],
            destination: {'addressId': _addressId},
            route: {'provider': 'LOCAL_ESTIMATE', 'estimated': true},
            warehouseSelection: {'warehouseId': _skuId},
            paymentPreference: 'BANK_TRANSFER',
            requestedDeliveryDate: '2026-10-13',
          );
        }),
      )..sessionCredentials = _FakeCredentials();
      final repository = PurchaseRequestRepositoryImpl(api, keyStore);
      const readyDraft = PurchaseRequestDraftProjection(
        id: _draftId,
        clientAccountId: _accountId,
        status: 'READY_TO_SUBMIT',
        version: 4,
        etag: '"4"',
        lines: [],
      );

      await expectLater(
        repository.submit(
          readyDraft,
          scopeKey: 'membership-1|tenant-1|workspace-1',
        ),
        throwsA(isA<NexaApiFailure>()),
      );
      expect(keyStore.completed, isFalse);

      final result = await repository.submit(
        readyDraft,
        scopeKey: 'membership-1|tenant-1|workspace-1',
      );
      expect(result.status, 'SUBMITTED');
      expect(requestCount, 2);
      expect(keys.first, isNotNull);
      expect(keys[1], keys.first);
      expect(keyStore.completed, isTrue);
      api.close();
    },
  );
}

http.Response _draftResponse({
  required int version,
  required String etag,
  String status = 'DRAFT',
  List<Map<String, Object?>> lines = const [],
  Map<String, Object?>? destination,
  Map<String, Object?>? route,
  Map<String, Object?>? warehouseSelection,
  String? paymentPreference,
  String? requestedDeliveryDate,
  String? submittedAt,
}) => http.Response(
  jsonEncode(
    _draft(
      version: version,
      status: status,
      lines: lines,
      destination: destination,
      route: route,
      warehouseSelection: warehouseSelection,
      paymentPreference: paymentPreference,
      requestedDeliveryDate: requestedDeliveryDate,
      submittedAt: submittedAt,
    ),
  ),
  200,
  headers: {'etag': etag},
);

Map<String, Object?> _draft({
  required int version,
  String status = 'DRAFT',
  List<Map<String, Object?>> lines = const [],
  Map<String, Object?>? destination,
  Map<String, Object?>? route,
  Map<String, Object?>? warehouseSelection,
  String? paymentPreference,
  String? requestedDeliveryDate,
  String? submittedAt,
}) => {
  'id': _draftId,
  'clientAccountId': _accountId,
  'buyerMembershipId': _membershipId,
  'status': status,
  'version': version,
  'requestedDeliveryDate': requestedDeliveryDate,
  'paymentPreference': paymentPreference,
  'creditResult': 'NOT_APPLICABLE',
  'routeProvider': route?['provider'],
  'lines': lines,
  'destination': destination,
  'route': route,
  'warehouseSelection': warehouseSelection,
  'createdAt': '2026-10-09T12:00:00Z',
  'updatedAt': '2026-10-09T12:00:00Z',
  'submittedAt': submittedAt,
};

Map<String, Object?> _line() => {
  'id': '00000000-0000-4000-8000-000000000007',
  'skuId': _skuId,
  'skuCode': 'SKU-1',
  'presentation': 'Caja',
  'quantity': 2,
  'unit': 'BOX',
  'effectiveUnitPrice': 12.50,
  'currency': 'PEN',
};

final class _RecordingKeyStore implements PurchaseRequestIdempotencyStore {
  String? scopeKey;
  String? draftId;
  int? version;
  bool completed = false;
  PurchaseRequestCreationRecord? creation;

  @override
  Future<String> keyFor({
    required String scopeKey,
    required String draftId,
    required int version,
  }) async {
    this.scopeKey = scopeKey;
    this.draftId = draftId;
    this.version = version;
    return '00000000-0000-4000-8000-000000000008';
  }

  @override
  Future<void> markCompleted({
    required String scopeKey,
    required String draftId,
    required int version,
  }) async {
    completed = true;
  }

  @override
  Future<PurchaseRequestCreationRecord?> creationRecord(
    String scopeKey,
  ) async => creation;

  @override
  Future<void> beginCreation(String scopeKey) async {
    creation = const PurchaseRequestCreationRecord.unknown();
  }

  @override
  Future<void> recordCreatedDraft(String scopeKey, String draftId) async {
    creation = PurchaseRequestCreationRecord.known(draftId);
  }

  @override
  Future<void> clearCreationRecord(String scopeKey) async {
    creation = null;
  }
}

final class _FakeCredentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'access-secret';

  @override
  int get authorityEpoch => 1;

  @override
  String get authorityFingerprint => '1';

  @override
  Future<String?> refreshIfCurrent(String tokenUsed, int epoch) async => null;
}
