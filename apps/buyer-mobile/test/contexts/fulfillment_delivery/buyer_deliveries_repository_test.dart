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

  test('loads only the Buyer delivery detail and event contract', () async {
    final requests = <http.Request>[];
    final api = _api((request) async {
      requests.add(request);
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
