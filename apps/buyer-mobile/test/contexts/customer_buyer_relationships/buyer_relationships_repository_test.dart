import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/customer_buyer_relationships/application/buyer_addresses_repository.dart';
import 'package:nexa_buyer_mobile/contexts/customer_buyer_relationships/infrastructure/buyer_addresses_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/customer_buyer_relationships/infrastructure/buyer_favorites_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _addressId = '00000000-0000-4000-8000-000000000041';
const _skuId = '00000000-0000-4000-8000-000000000042';

void main() {
  test('lists only parsed Buyer-owned addresses', () async {
    http.Request? request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode([
          _address(
            id: _addressId,
            label: 'Casa',
            version: 3,
            defaultAddress: true,
          ),
        ]),
        200,
      );
    });

    final addresses = await BuyerAddressesRepositoryImpl(api).list();

    expect(request!.method, 'GET');
    expect(request!.url.path, '/api/v1/buyer/addresses');
    expect(addresses.single.id, _addressId);
    expect(addresses.single.version, 3);
    expect(addresses.single.isDefault, isTrue);
    expect(addresses.single.text('districtCode'), '150101');
    api.close();
  });

  test(
    'creates and updates addresses with server version preconditions',
    () async {
      final requests = <http.Request>[];
      final api = _api((request) async {
        requests.add(request);
        final version = request.method == 'POST' ? 1 : 5;
        return http.Response(
          jsonEncode(_address(id: _addressId, version: version)),
          request.method == 'POST' ? 201 : 200,
        );
      });
      final repository = BuyerAddressesRepositoryImpl(api);
      final edit = BuyerAddressEdit(
        label: 'Casa',
        details: const {'addressType': 'STREET', 'districtCode': '150101'},
      );

      final created = await repository.save(edit);
      final current = BuyerAddress(
        id: _addressId,
        label: 'Casa',
        version: 4,
        isDefault: false,
        details: const {},
      );
      final updated = await repository.save(edit, current: current);

      expect(requests[0].method, 'POST');
      expect(requests[0].url.path, '/api/v1/buyer/addresses');
      expect(jsonDecode(requests[0].body), {
        'label': 'Casa',
        'address': {'addressType': 'STREET', 'districtCode': '150101'},
        'defaultAddress': false,
      });
      expect(requests[1].method, 'PATCH');
      expect(requests[1].url.path, '/api/v1/buyer/addresses/$_addressId');
      expect(
        requests[1].headers['if-match'] ?? requests[1].headers['If-Match'],
        '"4"',
      );
      expect(created.version, 1);
      expect(updated.version, 5);
      api.close();
    },
  );

  test(
    'loads only active reference options with selected parent scope',
    () async {
      http.Request? request;
      final api = _api((value) async {
        request = value;
        return http.Response(
          jsonEncode([
            {'code': '150101', 'label': 'Lima', 'active': true},
            {'code': '150102', 'label': 'Inactive', 'active': false},
          ]),
          200,
        );
      });

      final options = await BuyerAddressesRepositoryImpl(api)
          .options('districts', parent: '1501');

      expect(request!.url.path, '/api/v1/reference/districts');
      expect(request!.url.queryParameters, {'parentCode': '1501'});
      expect(options.map((option) => option.code), ['150101']);
      api.close();
    },
  );

  test('lists Buyer favorites using bounded server pagination', () async {
    http.Request? request;
    final api = _api((value) async {
      request = value;
      return http.Response(
        jsonEncode({
          'items': [
            {
              'sellableSkuId': _skuId,
              'currentOffer': {
                'sellableSkuId': _skuId,
                'catalogItemId': 'CAT-0001',
                'itemName': 'Café de altura',
                'brandName': 'Valle',
                'unitOfMeasure': 'UN',
                'currentPrice': {'amount': '12.50', 'currency': 'PEN'},
              },
            },
          ],
          'page': 2,
          'totalPages': 4,
        }),
        200,
      );
    });

    final page = await BuyerFavoritesRepositoryImpl(api).list(page: 2);

    expect(request!.method, 'GET');
    expect(request!.url.path, '/api/v1/buyer/favorites');
    expect(request!.url.queryParameters, {'page': '2', 'size': '25'});
    expect(page.page, 2);
    expect(page.totalPages, 4);
    expect(page.items.single.sellableSkuId, _skuId);
    expect(page.items.single.name, 'Café de altura');
    expect(page.items.single.amount, '12.50');
    api.close();
  });

  test('reads, saves, and removes only the selected SKU favorite', () async {
    final requests = <http.Request>[];
    final api = _api((request) async {
      requests.add(request);
      if (request.method == 'GET') {
        return http.Response(
          jsonEncode({'sellableSkuId': _skuId, 'saved': true}),
          200,
        );
      }
      if (request.method == 'PUT') {
        return http.Response(
          jsonEncode({'sellableSkuId': _skuId, 'currentOffer': null}),
          200,
        );
      }
      return http.Response('', 204);
    });
    final repository = BuyerFavoritesRepositoryImpl(api);

    expect(await repository.isSaved(_skuId), isTrue);
    await repository.save(_skuId);
    await repository.remove(_skuId);

    expect(requests.map((request) => request.method), ['GET', 'PUT', 'DELETE']);
    expect(requests.map((request) => request.url.path), [
      '/api/v1/buyer/favorites/$_skuId',
      '/api/v1/buyer/favorites/$_skuId',
      '/api/v1/buyer/favorites/$_skuId',
    ]);
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

Map<String, Object?> _address({
  String id = _addressId,
  String label = 'Casa',
  int version = 1,
  bool defaultAddress = false,
}) => {
  'id': id,
  'label': label,
  'addressType': 'STREET',
  'line': 'Av. Lima 123',
  'districtCode': '150101',
  'defaultAddress': defaultAddress,
  'active': true,
  'version': version,
};

final class _FakeCredentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'access-secret';

  @override
  int get authorityEpoch => 1;

  @override
  String get authorityFingerprint => 'membership|tenant|workspace|1';

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => null;
}
