import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/buyer_deliveries_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000001';

void main() {
  test(
    'lists the server-scoped Buyer delivery page without account selectors',
    () async {
      http.Request? request;
      final api = _api((value) async {
        request = value;
        return http.Response(
          jsonEncode({
            'items': [_delivery()],
            'page': 1,
            'size': 25,
            'total': 26,
            'clientAccountId': 'IGNORED ACCOUNT SELECTOR',
          }),
          200,
        );
      });

      final page = await BuyerDeliveriesRepositoryImpl(api).list(page: 1);

      expect(request!.method, 'GET');
      expect(request!.url.path, '/api/v1/buyer/deliveries');
      expect(request!.url.queryParameters, {'page': '1', 'size': '25'});
      expect(request!.headers['authorization'], 'Bearer access-secret');
      expect(request!.headers['x-nexa-client'], 'NATIVE');
      expect(request!.headers['x-nexa-surface'], 'PORTAL');
      expect(page.page, 1);
      expect(page.size, 25);
      expect(page.total, 26);
      expect(page.items.single.id, _deliveryId);
      expect(page.items.single.salesOrderNumber, 'SO-0001');
      expect(page.items.single.status, 'DISPATCHED');
      expect(page.items.single.destination, 'Sucursal principal');
      expect(page.items.single.dispatchedAt, DateTime.utc(2026, 10, 9, 12));
      expect(page.items.single.proofOfDeliveryStatus, isNull);
      api.close();
    },
  );

  test('loads Buyer delivery detail, event and receipt contracts', () async {
    final requests = <http.Request>[];
    final api = _api((request) async {
      requests.add(request);
      if (request.url.path.endsWith('/buyer-receipts')) {
        return http.Response('[]', 200);
      }
      if (request.url.path.endsWith('/events')) {
        return http.Response(
          jsonEncode([
            {
              'type': 'HANDED_OVER',
              'occurredAt': '2026-10-09T12:00:00Z',
              'actorMembershipId': 'PRIVATE ACTOR',
              'reason': 'PRIVATE INTERNAL REASON',
            },
          ]),
          200,
        );
      }
      return http.Response(jsonEncode(_delivery()), 200);
    });

    final detail = await BuyerDeliveriesRepositoryImpl(api).detail(_deliveryId);

    expect(
      requests.map((request) => request.url.path),
      containsAll([
        '/api/v1/buyer/deliveries/$_deliveryId',
        '/api/v1/buyer/deliveries/$_deliveryId/events',
        '/api/v1/buyer/deliveries/$_deliveryId/buyer-receipts',
      ]),
    );
    expect(requests.every((request) => request.method == 'GET'), isTrue);
    expect(
      requests.every((request) => request.url.queryParameters.isEmpty),
      isTrue,
    );
    expect(detail.delivery.id, _deliveryId);
    expect(detail.delivery.salesOrderNumber, 'SO-0001');
    expect(detail.delivery.status, 'DISPATCHED');
    expect(detail.delivery.scheduledAt, isNull);
    expect(detail.delivery.deliveredAt, isNull);
    expect(detail.delivery.proofOfDeliveryStatus, isNull);
    expect(detail.events.single.type, 'HANDED_OVER');
    expect(detail.events.single.occurredAt, DateTime.utc(2026, 10, 9, 12));
    api.close();
  });

  test('loads disputed receipt evidence through scoped BC09 query', () async {
    final requests = <http.Request>[];
    const receiptId = '00000000-0000-4000-8000-000000000030';
    const evidenceId = '00000000-0000-4000-8000-000000000031';
    final api = _api((request) async {
      requests.add(request);
      if (request.url.path.endsWith('/events')) return http.Response('[]', 200);
      if (request.url.path.endsWith('/buyer-receipts')) {
        return http.Response(jsonEncode([_buyerReceipt(receiptId)]), 200);
      }
      if (request.url.path == '/api/v1/business-document-evidence') {
        return http.Response(
          jsonEncode({
            'items': [_evidence(evidenceId, receiptId)],
            'page': 0,
            'size': 100,
            'total': 1,
          }),
          200,
        );
      }
      return http.Response(jsonEncode(_delivery()), 200);
    });

    final detail = await BuyerDeliveriesRepositoryImpl(api).detail(_deliveryId);

    final receipt = detail.receipts.single;
    expect(receipt.id, receiptId);
    expect(receipt.decision, 'DISPUTED');
    expect(
      receipt.recordedByMembershipId,
      '00000000-0000-4000-8000-000000000020',
    );
    expect(receipt.evidence.single.id, evidenceId);
    expect(receipt.evidence.single.lifecycleStatus, 'QUARANTINED');
    final evidenceRequest = requests.singleWhere(
      (request) => request.url.path == '/api/v1/business-document-evidence',
    );
    expect(evidenceRequest.url.queryParameters, {
      'subjectType': 'BUYER_RECEIPT',
      'subjectId': receiptId,
      'page': '0',
      'size': '100',
    });
    api.close();
  });

  test(
    'uploads evidence as authenticated multipart with idempotency key',
    () async {
      http.Request? request;
      final api = _api((value) async {
        request = value;
        return http.Response(
          jsonEncode(
            _evidence(
              '00000000-0000-4000-8000-000000000032',
              '00000000-0000-4000-8000-000000000030',
            ),
          ),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final evidence = await BuyerDeliveriesRepositoryImpl(api)
          .uploadReceiptEvidence(
            receiptId: '00000000-0000-4000-8000-000000000030',
            filename: 'receipt.png',
            contentType: 'image/png',
            bytes: [1, 2, 3, 4],
            idempotencyKey: '00000000-0000-4000-8000-000000000033',
          );

      expect(request!.method, 'POST');
      expect(request!.url.path, '/api/v1/business-document-evidence');
      expect(request!.headers['authorization'], 'Bearer access-secret');
      expect(request!.headers['x-nexa-client'], 'NATIVE');
      expect(request!.headers['x-nexa-surface'], 'PORTAL');
      expect(
        request!.headers['idempotency-key'],
        '00000000-0000-4000-8000-000000000033',
      );
      expect(
        request!.headers['content-type'],
        startsWith('multipart/form-data;'),
      );
      expect(request!.body, contains('BUYER_RECEIPT'));
      expect(request!.body, contains('00000000-0000-4000-8000-000000000030'));
      expect(request!.body, contains('filename="receipt.png"'));
      expect(evidence.lifecycleStatus, 'QUARANTINED');
      api.close();
    },
  );

  test('reads the server-filtered active delivery location contract', () async {
    http.Request? request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode({
          'deliveryId': _deliveryId,
          'location': {
            'sampleId': '00000000-0000-4000-8000-000000000020',
            'latitude': -12.0464,
            'longitude': -77.0428,
            'accuracyMeters': 14.5,
            'capturedAt': '2026-10-09T12:00:00Z',
            'expiresAt': '2026-10-09T12:02:00Z',
          },
        }),
        200,
      );
    });

    final location = (await BuyerDeliveriesRepositoryImpl(api)
        .liveLocation(_deliveryId))!;

    expect(request!.method, 'GET');
    expect(
      request!.url.path,
      '/api/v1/buyer/deliveries/$_deliveryId/live-location',
    );
    expect(request!.url.queryParameters, isEmpty);
    expect(location.deliveryId, _deliveryId);
    expect(location.sampleId, '00000000-0000-4000-8000-000000000020');
    expect(location.latitude, -12.0464);
    expect(location.longitude, -77.0428);
    expect(location.accuracyMeters, 14.5);
    expect(location.capturedAt, DateTime.utc(2026, 10, 9, 12));
    expect(location.expiresAt, DateTime.utc(2026, 10, 9, 12, 2));
    api.close();
  });

  test(
    'rejects out-of-range coordinates and mismatched delivery response',
    () async {
      final api = _api((_) async {
        return http.Response(
          jsonEncode({
            'deliveryId': '00000000-0000-4000-8000-000000000099',
            'location': {
              'sampleId': '00000000-0000-4000-8000-000000000020',
              'latitude': 123,
              'longitude': -77.0428,
              'accuracyMeters': 14.5,
              'capturedAt': '2026-10-09T12:00:00Z',
              'expiresAt': '2026-10-09T12:02:00Z',
            },
          }),
          200,
        );
      });

      await expectLater(
        BuyerDeliveriesRepositoryImpl(api).liveLocation(_deliveryId),
        throwsA(isA<NexaApiFailure>()),
      );
      api.close();
    },
  );

  test(
    'rejects malformed required timestamps and unknown delivery identifiers',
    () async {
      final api = _api((_) async {
        return http.Response(
          jsonEncode({
            'items': [
              {..._delivery(), 'updatedAt': 'not-a-date'},
            ],
            'page': 0,
            'size': 25,
            'total': 1,
          }),
          200,
        );
      });

      await expectLater(
        BuyerDeliveriesRepositoryImpl(api).list(page: 0),
        throwsA(isA<NexaApiFailure>()),
      );
      await expectLater(
        BuyerDeliveriesRepositoryImpl(api).detail('not-a-uuid'),
        throwsA(isA<NexaApiFailure>()),
      );
      api.close();
    },
  );

  test('validates the Buyer handoff with only the presented code', () async {
    http.Request? request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode({
          'handoffId': '00000000-0000-4000-8000-000000000010',
          'deliveryId': _deliveryId,
          'attemptId': '00000000-0000-4000-8000-000000000011',
          'expiresAt': '2026-10-10T12:00:00Z',
          'deliveryStatus': 'IN_TRANSIT',
          'deliveredQuantity': 12.5,
        }),
        200,
      );
    });

    final validation = await BuyerDeliveriesRepositoryImpl(api)
        .validateHandoff(' buyer-code ');

    expect(request!.method, 'POST');
    expect(request!.url.path, '/api/v1/delivery-handoff/validations');
    expect(request!.url.queryParameters, isEmpty);
    expect(jsonDecode(request!.body), {'token': 'buyer-code'});
    expect(validation.deliveryId, _deliveryId);
    expect(validation.attemptId, '00000000-0000-4000-8000-000000000011');
    expect(validation.deliveredQuantity, 12.5);
    api.close();
  });

  test('accepts Buyer QR and parses linked delivery completion', () async {
    http.Request? request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode({
          'receiptId': '00000000-0000-4000-8000-000000000012',
          'deliveryId': _deliveryId,
          'attemptId': '00000000-0000-4000-8000-000000000011',
          'podId': '00000000-0000-4000-8000-000000000014',
          'acceptedQuantity': 12.5,
          'occurredAt': '2026-10-09T12:30:00Z',
          'deliveryStatus': 'DELIVERED',
          'podStatus': 'SEALED',
          'salesOrderStatus': 'COMPLETED',
          'deliveryVersion': 4,
          'orderCompleted': true,
          'replayed': false,
        }),
        201,
      );
    });

    final acceptance = await BuyerDeliveriesRepositoryImpl(api).acceptHandoff(
      token: ' buyer-code ',
      expectedDeliveryId: _deliveryId,
      idempotencyKey: '00000000-0000-4000-8000-000000000013',
    );

    expect(request!.method, 'POST');
    expect(request!.url.path, '/api/v1/delivery-handoff/receipts');
    expect(
      request!.headers['idempotency-key'],
      '00000000-0000-4000-8000-000000000013',
    );
    expect(jsonDecode(request!.body), {
      'token': 'buyer-code',
      'expectedDeliveryId': _deliveryId,
    });
    expect(acceptance.receiptId, '00000000-0000-4000-8000-000000000012');
    expect(acceptance.deliveryId, _deliveryId);
    expect(acceptance.attemptId, '00000000-0000-4000-8000-000000000011');
    expect(acceptance.podId, '00000000-0000-4000-8000-000000000014');
    expect(acceptance.acceptedQuantity, 12.5);
    expect(acceptance.deliveryVersion, 4);
    expect(acceptance.orderCompleted, isTrue);
    expect(acceptance.replayed, isFalse);
    api.close();
  });

  test(
    'records Buyer receipt with exact idempotency key and disputed quantity',
    () async {
      http.Request? request;
      final api = _api((value) async {
        request = value;
        return http.Response(
          jsonEncode({
            'id': '00000000-0000-4000-8000-000000000012',
            'deliveryId': _deliveryId,
            'attemptId': '00000000-0000-4000-8000-000000000011',
            'decision': 'DISPUTED',
            'driverDeliveredQuantity': 12.5,
            'acceptedQuantity': 10,
            'reason': 'Two units missing',
            'occurredAt': '2026-10-09T12:30:00Z',
            'replayed': true,
          }),
          200,
        );
      });

      final receipt = await BuyerDeliveriesRepositoryImpl(api).recordReceipt(
        deliveryId: _deliveryId,
        token: 'buyer-code',
        decision: 'DISPUTED',
        acceptedQuantity: 10,
        reason: 'Two units missing',
        idempotencyKey: '00000000-0000-4000-8000-000000000013',
      );

      expect(request!.method, 'POST');
      expect(
        request!.url.path,
        '/api/v1/deliveries/$_deliveryId/buyer-receipts',
      );
      expect(
        request!.headers['idempotency-key'],
        '00000000-0000-4000-8000-000000000013',
      );
      expect(jsonDecode(request!.body), {
        'token': 'buyer-code',
        'decision': 'DISPUTED',
        'acceptedQuantity': 10,
        'reason': 'Two units missing',
      });
      expect(receipt.id, '00000000-0000-4000-8000-000000000012');
      expect(receipt.attemptId, '00000000-0000-4000-8000-000000000011');
      expect(receipt.driverDeliveredQuantity, 12.5);
      expect(receipt.acceptedQuantity, 10);
      expect(receipt.reason, 'Two units missing');
      expect(receipt.replayed, isTrue);
      api.close();
    },
  );
}

NexaApiClient _api(Future<http.Response> Function(http.Request) handler) =>
    NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(handler),
    )..sessionCredentials = _FakeCredentials();

Map<String, Object?> _delivery() => {
  'id': _deliveryId,
  'salesOrderNumber': 'SO-0001',
  'status': 'DISPATCHED',
  'destination': 'Sucursal principal',
  'scheduledAt': null,
  'dispatchedAt': '2026-10-09T12:00:00Z',
  'deliveredAt': null,
  'proofOfDeliveryStatus': null,
  'version': 3,
  'createdAt': '2026-10-09T11:00:00Z',
  'updatedAt': '2026-10-09T12:05:00Z',
  'driverName': 'PRIVATE DRIVER NAME',
  'assignedDriver': 'PRIVATE ASSIGNMENT',
  'vehiclePlate': 'PRIVATE VEHICLE',
};

Map<String, Object?> _buyerReceipt(String id) => {
  'id': id,
  'deliveryId': _deliveryId,
  'attemptId': '00000000-0000-4000-8000-000000000011',
  'decision': 'DISPUTED',
  'driverDeliveredQuantity': 12.5,
  'acceptedQuantity': 10,
  'reason': 'Two units missing',
  'occurredAt': '2026-10-09T12:30:00Z',
  'recordedByMembershipId': '00000000-0000-4000-8000-000000000020',
};

Map<String, Object?> _evidence(String id, String receiptId) => {
  'id': id,
  'subjectType': 'BUYER_RECEIPT',
  'subjectId': receiptId,
  'lifecycleStatus': 'QUARANTINED',
  'declaredContentType': 'image/png',
  'detectedContentType': 'image/png',
  'originalFilename': 'receipt.png',
  'checksumSha256': List.filled(64, 'a').join(),
  'byteSize': 4,
  'createdAt': '2026-10-09T12:31:00Z',
  'scannedAt': null,
  'failureCode': null,
  'updatedAt': '2026-10-09T12:31:00Z',
  'requestedByMembershipId': '00000000-0000-4000-8000-000000000020',
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
