import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/buyer_deliveries_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _deliveryId = '00000000-0000-4000-8000-000000000001';
const _eventId = '00000000-0000-4000-8000-000000000002';

void main() {
  test(
    'lists Buyer deliveries without selecting an account or exposing PII',
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
            'clientAccountId': 'PRIVATE ACCOUNT ID',
          }),
          200,
        );
      });

      final page = await BuyerDeliveriesRepositoryImpl(api).list(page: 1);

      expect(request!.method, 'GET');
      expect(request!.url.path, '/api/v1/dispatch-orders');
      expect(request!.url.queryParameters, {'page': '1', 'size': '25'});
      expect(request!.headers['authorization'], 'Bearer access-secret');
      expect(request!.headers['x-nexa-client'], 'NATIVE');
      expect(request!.headers['x-nexa-surface'], 'PORTAL');
      expect(page.page, 1);
      expect(page.size, 25);
      expect(page.total, 26);
      expect(page.items.single.dispatchNumber, 'DO-0001');
      expect(page.items.single.destination, 'Sucursal principal');
      expect(
        page.items.single.dispatchNumber,
        isNot(contains('PRIVATE DRIVER')),
      );
      api.close();
    },
  );

  test(
    'loads only safe detail fields and Buyer-visible event summaries',
    () async {
      final requests = <http.Request>[];
      final api = _api((request) async {
        requests.add(request);
        if (request.url.path.endsWith('/events')) {
          return http.Response(
            jsonEncode([
              {
                'id': _eventId,
                'type': 'IN_TRANSIT',
                'occurredAt': '2026-10-09T12:00:00Z',
                'summary': 'La entrega está en tránsito.',
                'fromStatus': 'PRIVATE',
                'toStatus': 'PRIVATE',
                'driverName': 'PRIVATE DRIVER NAME',
              },
            ]),
            200,
          );
        }
        return http.Response(jsonEncode(_delivery()), 200);
      });

      final detail = await BuyerDeliveriesRepositoryImpl(api)
          .detail(_deliveryId);

      expect(
        requests.map((request) => request.url.path),
        containsAll([
          '/api/v1/dispatch-orders/$_deliveryId',
          '/api/v1/dispatch-orders/$_deliveryId/events',
        ]),
      );
      expect(requests.every((request) => request.method == 'GET'), isTrue);
      expect(
        requests.every((request) => request.url.queryParameters.isEmpty),
        isTrue,
      );
      expect(detail.delivery.id, _deliveryId);
      expect(detail.delivery.eta, DateTime.utc(2026, 10, 9, 13));
      expect(detail.events.single.summary, 'La entrega está en tránsito.');
      expect(
        detail.events.single.toString(),
        isNot(contains('PRIVATE DRIVER')),
      );
      api.close();
    },
  );

  test('rejects invalid non-null delivery timestamps', () async {
    final api = _api((_) async {
      return http.Response(
        jsonEncode({
          'items': [
            {..._delivery(), 'eta': 'not-a-date'},
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

Map<String, Object?> _delivery() => {
  'id': _deliveryId,
  'dispatchNumber': 'DO-0001',
  'salesOrderNumber': 'SO-0001',
  'status': 'IN_TRANSIT',
  'destination': 'Sucursal principal',
  'deliveryWindowStart': '2026-10-09T12:00:00Z',
  'deliveryWindowEnd': '2026-10-09T14:00:00Z',
  'eta': '2026-10-09T13:00:00Z',
  'podStatus': 'PENDING',
  'updatedAt': '2026-10-09T12:05:00Z',
  'alerts': ['ARRIVAL_WINDOW'],
  'continuationDeliveryStatus': null,
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
