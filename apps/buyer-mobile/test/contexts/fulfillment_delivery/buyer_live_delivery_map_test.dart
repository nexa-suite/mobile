import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:latlong2/latlong.dart';
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/presentation/buyer_live_delivery_map.dart';

void main() {
  testWidgets('renders the vehicle point, street map and accessible controls', (
    tester,
  ) async {
    final tileProvider = _RecordingTileProvider();
    const latitude = -12.0464;
    const longitude = -77.0428;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 320,
            height: 240,
            child: BuyerLiveDeliveryMap(
              latitude: latitude,
              longitude: longitude,
              tileUrlTemplate: 'https://tiles.example.test/{z}/{x}/{y}.png',
              attribution: 'Mapa de prueba',
              tileProvider: tileProvider,
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final map = tester.widget<FlutterMap>(find.byType(FlutterMap));
    expect(map.options.initialCenter, const LatLng(latitude, longitude));
    expect(map.options.initialZoom, 15);
    expect(map.options.minZoom, 3);
    expect(map.options.maxZoom, 18);
    expect(
      map.options.interactionOptions.flags,
      InteractiveFlag.drag |
          InteractiveFlag.pinchZoom |
          InteractiveFlag.doubleTapZoom,
    );

    final layer = tester.widget<TileLayer>(find.byType(TileLayer));
    expect(layer.urlTemplate, 'https://tiles.example.test/{z}/{x}/{y}.png');
    expect(layer.panBuffer, 0);
    expect(layer.keepBuffer, 0);
    expect(layer.maxZoom, 18);
    expect(layer.maxNativeZoom, 18);
    expect(tileProvider.requestedTiles, isNotEmpty);
    expect(tileProvider.requestedTiles.length, lessThanOrEqualTo(8));

    final marker = tester.widget<MarkerLayer>(find.byType(MarkerLayer));
    expect(marker.markers, hasLength(1));
    expect(marker.markers.single.point, const LatLng(latitude, longitude));
    expect(find.bySemanticsLabel('Ubicación del vehículo'), findsOneWidget);
    expect(find.text('Mapa de prueba'), findsOneWidget);
    expect(find.byTooltip('Volver al vehículo'), findsOneWidget);
    expect(find.byTooltip('Seguir vehículo'), findsOneWidget);
    expect(find.byTooltip('Acercar mapa'), findsOneWidget);
    expect(find.byTooltip('Alejar mapa'), findsOneWidget);

    await tester.tap(find.byTooltip('Seguir vehículo'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('Dejar de seguir vehículo'), findsOneWidget);
    await tester.tap(find.byTooltip('Dejar de seguir vehículo'));
    await tester.tap(find.byTooltip('Acercar mapa'));
    await tester.tap(find.byTooltip('Alejar mapa'));
    await tester.tap(find.byTooltip('Volver al vehículo'));
    await tester.pumpAndSettle();
  });
}

final class _RecordingTileProvider extends TileProvider {
  final requestedTiles = <TileCoordinates>[];

  @override
  ImageProvider getImage(TileCoordinates coordinates, TileLayer options) {
    requestedTiles.add(coordinates);
    return MemoryImage(TileProvider.transparentImage);
  }
}
