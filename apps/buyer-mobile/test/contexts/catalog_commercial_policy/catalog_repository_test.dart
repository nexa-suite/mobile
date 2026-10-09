import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/infrastructure/catalog_repository_impl.dart';
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
