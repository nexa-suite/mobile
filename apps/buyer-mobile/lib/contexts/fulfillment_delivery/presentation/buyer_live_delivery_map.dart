import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

final class BuyerLiveDeliveryMap extends StatefulWidget {
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
  State<BuyerLiveDeliveryMap> createState() => _BuyerLiveDeliveryMapState();
}

final class _BuyerLiveDeliveryMapState extends State<BuyerLiveDeliveryMap> {
  final MapController _mapController = MapController();
  bool _mapReady = false;

  @override
  void didUpdateWidget(covariant BuyerLiveDeliveryMap oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_mapReady ||
        (oldWidget.latitude == widget.latitude &&
            oldWidget.longitude == widget.longitude)) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_mapReady) return;
      _mapController.move(LatLng(widget.latitude, widget.longitude), 15);
    });
  }

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(12),
    child: Stack(
      children: [
        FlutterMap(
          mapController: _mapController,
          options: MapOptions(
            initialCenter: LatLng(widget.latitude, widget.longitude),
            initialZoom: 15,
            minZoom: 15,
            maxZoom: 15,
            onMapReady: () => _mapReady = true,
            interactionOptions: const InteractionOptions(
              flags: InteractiveFlag.none,
            ),
          ),
          children: [
            TileLayer(
              urlTemplate: widget.tileUrlTemplate,
              userAgentPackageName: 'com.nexa.nexa_buyer_mobile',
              tileProvider: widget.tileProvider,
              maxNativeZoom: 15,
              maxZoom: 15,
              panBuffer: 0,
              keepBuffer: 0,
            ),
            MarkerLayer(
              markers: [
                Marker(
                  point: LatLng(widget.latitude, widget.longitude),
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
                widget.attribution,
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
          ),
        ),
      ],
    ),
  );
}
