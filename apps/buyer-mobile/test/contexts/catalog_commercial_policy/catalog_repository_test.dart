import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/infrastructure/catalog_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  test(
    'keeps the current offer unavailable when only base prices exist',
    () async {
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient(
          (request) async => http.Response(
            jsonEncode({
              'items': [
                {
                  'catalogItemId': 'CAT-0001',
                  'sellableSkuId': null,
                  'itemName': 'Fixture item',
                  'availabilityStatus': 'AVAILABLE',
                  'currentOfferPrice': null,
                  'effectivePrice': {'amount': '12.00', 'currency': 'PEN'},
                  'unitPrice': {'amount': '13.00', 'currency': 'PEN'},
                  'basePrice': {'amount': '14.00', 'currency': 'PEN'},
                },
              ],
              'page': 0,
              'size': 20,
              'totalItems': 1,
              'totalPages': 1,
            }),
            200,
          ),
        ),
      )..sessionCredentials = _FakeCredentials();

      final catalog = await CatalogRepositoryImpl(api).list(query: '', page: 0);

      expect(catalog.items.single.price, isNull);
      api.close();
    },
  );

  test(
    'sends brand and type filters to the server alongside product query',
    () async {
      http.Request? request;
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((value) async {
          request = value;
          return http.Response(
            jsonEncode({
              'items': [],
              'page': 2,
              'size': 20,
              'totalItems': 41,
              'totalPages': 3,
            }),
            200,
          );
        }),
      )..sessionCredentials = _FakeCredentials();

      final result = await CatalogRepositoryImpl(api).list(
        query: ' leche ',
        brand: ' Valle ',
        category: ' Bebidas ',
        page: 2,
      );

      expect(request!.method, 'GET');
      expect(request!.url.path, '/api/v1/catalog-items');
      expect(request!.url.queryParameters, {
        'q': 'leche',
        'brand': 'Valle',
        'category': 'Bebidas',
        'page': '2',
        'size': '20',
        'sort': 'itemName',
        'direction': 'asc',
      });
      expect(result.page, 2);
      expect(result.totalItems, 41);
      expect(result.totalPages, 3);
      api.close();
    },
  );

  test(
    'loads Buyer-safe taxonomy options with search and pagination',
    () async {
      http.Request? request;
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((value) async {
          request = value;
          return http.Response(
            jsonEncode({
              'items': [
                {'id': '00000000-0000-4000-8000-000000000011', 'name': 'Valle'},
              ],
              'page': 1,
              'size': 100,
              'total': 101,
            }),
            200,
          );
        }),
      )..sessionCredentials = _FakeCredentials();

      final result = await CatalogRepositoryImpl(
        api,
      ).filterOptions(kind: CatalogFilterKind.brand, search: ' Val ', page: 1);

      expect(request!.method, 'GET');
      expect(request!.url.path, '/api/v1/catalog-items/filter-options');
      expect(request!.url.queryParameters, {
        'kind': 'brand',
        'search': 'Val',
        'page': '1',
        'size': '100',
      });
      expect(result.items.single.id, '00000000-0000-4000-8000-000000000011');
      expect(result.items.single.name, 'Valle');
      expect(result.totalPages, 2);
      api.close();
    },
  );
}

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
