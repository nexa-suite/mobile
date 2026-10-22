import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

final class BuyerLiveDeliveryMap extends StatelessWidget {
  const BuyerLiveDeliveryMap({
    super.key,
    required this.latitude,
    required this.longitude,
    required this.tileUrlTemplate,
    required this.attribution,
    this.tileProvider,
  });

  final double latitude;
  final double longitude;
  final String tileUrlTemplate;
  final String attribution;
  final TileProvider? tileProvider;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(12),
    child: Stack(
      children: [
        FlutterMap(
          options: MapOptions(
            initialCenter: LatLng(latitude, longitude),
            initialZoom: 15,
            minZoom: 15,
            maxZoom: 15,
            interactionOptions: const InteractionOptions(
              flags: InteractiveFlag.none,
            ),
          ),
          children: [
            TileLayer(
              urlTemplate: tileUrlTemplate,
              userAgentPackageName: 'com.nexa.nexa_buyer_mobile',
              tileProvider: tileProvider,
              maxNativeZoom: 15,
              maxZoom: 15,
              panBuffer: 0,
              keepBuffer: 0,
            ),
            MarkerLayer(
              markers: [
                Marker(
                  point: LatLng(latitude, longitude),
                  width: 48,
                  height: 48,
                  child: Icon(
                    Icons.local_shipping,
                    color: Theme.of(context).colorScheme.primary,
                    size: 40,
                    semanticLabel: 'Ubicación del vehículo',
                  ),
                ),
              ],
            ),
          ],
        ),
        Positioned(
          right: 4,
          bottom: 4,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface
                  .withValues(alpha: 0.9),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
              child: Text(
                attribution,
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
          ),
        ),
      ],
    ),
  );
}
