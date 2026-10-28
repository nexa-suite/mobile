import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/core/configuration/buyer_map_configuration.dart';

void main() {
  test('debug defaults to attributed OpenStreetMap tiles', () {
    final configuration = BuyerMapConfiguration.resolve(
      tileUrlTemplate: '',
      attribution: '',
      isDebugBuild: true,
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

  test('release requires an explicit tile source', () {
    final configuration = BuyerMapConfiguration.resolve(
      tileUrlTemplate: '',
      attribution: '',
    );

    expect(configuration.tileUrlTemplate, isEmpty);
    expect(configuration.attribution, isEmpty);
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
