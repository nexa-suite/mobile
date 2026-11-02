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
  bool _followingVehicle = false;

  @override
  void dispose() {
    _mapController.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant BuyerLiveDeliveryMap oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_mapReady ||
        !_followingVehicle ||
        (oldWidget.latitude == widget.latitude &&
            oldWidget.longitude == widget.longitude)) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_mapReady || !_followingVehicle) return;
      _mapController.move(
        LatLng(widget.latitude, widget.longitude),
        _mapController.camera.zoom,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: Stack(
        children: [
          FlutterMap(
            mapController: _mapController,
            options: MapOptions(
              initialCenter: LatLng(widget.latitude, widget.longitude),
              initialZoom: 15,
              minZoom: 3,
              maxZoom: 18,
              onMapReady: () {
                if (mounted) setState(() => _mapReady = true);
              },
              interactionOptions: const InteractionOptions(
                flags:
                    InteractiveFlag.drag |
                    InteractiveFlag.pinchZoom |
                    InteractiveFlag.doubleTapZoom,
              ),
            ),
            children: [
              TileLayer(
                urlTemplate: widget.tileUrlTemplate,
                userAgentPackageName: 'com.nexa.nexa_buyer_mobile',
                tileProvider: widget.tileProvider,
                maxNativeZoom: 18,
                maxZoom: 18,
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
                      color: theme.colorScheme.primary,
                      size: 40,
                      semanticLabel: 'Ubicación del vehículo',
                    ),
                  ),
                ],
              ),
            ],
          ),
          Positioned(
            left: 8,
            top: 8,
            child: Material(
              color: theme.colorScheme.surface.withValues(alpha: 0.94),
              borderRadius: BorderRadius.circular(12),
              elevation: 2,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: 'Volver al vehículo',
                    onPressed: _mapReady ? _centerOnVehicle : null,
                    icon: const Icon(Icons.my_location),
                  ),
                  IconButton(
                    tooltip: _followingVehicle
                        ? 'Dejar de seguir vehículo'
                        : 'Seguir vehículo',
                    onPressed: _mapReady ? _toggleFollowing : null,
                    icon: Icon(
                      _followingVehicle ? Icons.gps_fixed : Icons.gps_not_fixed,
                    ),
                  ),
                ],
              ),
            ),
          ),
          Positioned(
            right: 8,
            top: 8,
            child: Material(
              color: theme.colorScheme.surface.withValues(alpha: 0.94),
              borderRadius: BorderRadius.circular(12),
              elevation: 2,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: 'Acercar mapa',
                    onPressed: _mapReady ? () => _zoomBy(1) : null,
                    icon: const Icon(Icons.add),
                  ),
                  IconButton(
                    tooltip: 'Alejar mapa',
                    onPressed: _mapReady ? () => _zoomBy(-1) : null,
                    icon: const Icon(Icons.remove),
                  ),
                ],
              ),
            ),
          ),
          Positioned(
            right: 4,
            bottom: 4,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: theme.colorScheme.surface.withValues(alpha: 0.9),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                child: Text(
                  widget.attribution,
                  style: theme.textTheme.labelSmall,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _centerOnVehicle() {
    if (!_mapReady) return;
    _mapController.move(
      LatLng(widget.latitude, widget.longitude),
      _mapController.camera.zoom,
    );
  }

  void _toggleFollowing() {
    if (!_mapReady) return;
    setState(() => _followingVehicle = !_followingVehicle);
    if (_followingVehicle) _centerOnVehicle();
  }

  void _zoomBy(double delta) {
    if (!_mapReady) return;
    final camera = _mapController.camera;
    _mapController.move(
      camera.center,
      (camera.zoom + delta).clamp(3, 18).toDouble(),
    );
  }
}
