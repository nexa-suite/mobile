import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/core/configuration/buyer_map_configuration.dart';

void main() {
  test('defaults to attributed OpenStreetMap tiles', () {
    final configuration = BuyerMapConfiguration.resolve(
      tileUrlTemplate: '',
      attribution: '',
    );

    expect(
      configuration.tileUrlTemplate,
      BuyerMapConfiguration.openStreetMapTileUrl,
    );
    expect(
      configuration.attribution,
      BuyerMapConfiguration.openStreetMapAttribution,
    );
  });

  test('uses configured source and attribution', () {
    final configuration = BuyerMapConfiguration.resolve(
      tileUrlTemplate: ' https://tiles.example.test/{z}/{x}/{y}.png ',
      attribution: ' Example Maps ',
    );

    expect(
      configuration.tileUrlTemplate,
      'https://tiles.example.test/{z}/{x}/{y}.png',
    );
    expect(configuration.attribution, 'Example Maps');
  });

  test('requires attribution for a custom tile source', () {
    final configuration = BuyerMapConfiguration.resolve(
      tileUrlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
      attribution: '',
    );

    expect(configuration.attribution, isEmpty);
  });
}
