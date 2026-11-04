import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../contexts/customer_buyer_relationships/presentation/buyer_address_editor.dart';

class BuyerAddressLocationPickerPage extends StatefulWidget {
  const BuyerAddressLocationPickerPage({
    super.key,
    required this.tileUrlTemplate,
    required this.attribution,
    this.initial,
  });
  final String tileUrlTemplate, attribution;
  final BuyerAddressLocation? initial;
  @override
  State<BuyerAddressLocationPickerPage> createState() =>
      _BuyerAddressLocationPickerPageState();
}

class _BuyerAddressLocationPickerPageState
    extends State<BuyerAddressLocationPickerPage> {
  final _map = MapController();
  bool _ready = false;
  LatLng? _selected;
  @override
  void initState() {
    super.initState();
    final initial = widget.initial;
    if (initial != null) {
      _selected = LatLng(initial.latitude, initial.longitude);
    }
  }

  @override
  void dispose() {
    _map.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Punto de entrega')),
    body: SafeArea(
      child: Column(
        children: [
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text(
              'Busca tu dirección moviendo el mapa y toca el punto exacto. También puedes ajustar el punto con las flechas.',
            ),
          ),
          Expanded(
            child: Stack(
              children: [
                FlutterMap(
                  mapController: _map,
                  options: MapOptions(
                    initialCenter: _selected ?? const LatLng(-9.19, -75.015),
                    initialZoom: _selected == null ? 5 : 16,
                    minZoom: 3,
                    maxZoom: 18,
                    onMapReady: () => setState(() => _ready = true),
                    onTap: (_, point) => setState(() => _selected = point),
                  ),
                  children: [
                    TileLayer(
                      urlTemplate: widget.tileUrlTemplate,
                      userAgentPackageName: 'com.nexa.nexa_buyer_mobile',
                      maxNativeZoom: 18,
                    ),
                    if (_selected case final point?)
                      MarkerLayer(
                        markers: [
                          Marker(
                            point: point,
                            width: 48,
                            height: 48,
                            child: const Icon(
                              Icons.location_pin,
                              size: 48,
                              semanticLabel: 'Punto de entrega seleccionado',
                            ),
                          ),
                        ],
                      ),
                  ],
                ),
                Positioned(
                  bottom: 8,
                  left: 8,
                  right: 8,
                  child: Material(
                    color: Theme.of(context).colorScheme.surface,
                    child: Padding(
                      padding: const EdgeInsets.all(8),
                      child: Text(widget.attribution),
                    ),
                  ),
                ),
              ],
            ),
          ),
          Wrap(
            alignment: WrapAlignment.center,
            children: [
              _move('Mover al oeste', Icons.arrow_back, -1, 0),
              _move('Mover al norte', Icons.arrow_upward, 0, 1),
              _move('Mover al sur', Icons.arrow_downward, 0, -1),
              _move('Mover al este', Icons.arrow_forward, 1, 0),
              IconButton(
                tooltip: 'Acercar mapa',
                onPressed: _ready
                    ? () => _map.move(
                        _map.camera.center,
                        (_map.camera.zoom + 1).clamp(3, 18),
                      )
                    : null,
                icon: const Icon(Icons.add),
              ),
              IconButton(
                tooltip: 'Alejar mapa',
                onPressed: _ready
                    ? () => _map.move(
                        _map.camera.center,
                        (_map.camera.zoom - 1).clamp(3, 18),
                      )
                    : null,
                icon: const Icon(Icons.remove),
              ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: _selected == null
                    ? null
                    : () => Navigator.pop(context, (
                        latitude: _selected!.latitude,
                        longitude: _selected!.longitude,
                      )),
                child: const Text('Usar este punto'),
              ),
            ),
          ),
        ],
      ),
    ),
  );
  Widget _move(String label, IconData icon, int x, int y) => IconButton(
    tooltip: label,
    icon: Icon(icon),
    onPressed: !_ready
        ? null
        : () {
            final center = _selected ?? _map.camera.center;
            final step = 360 / (1 << _map.camera.zoom.floor()) / 4;
            final point = LatLng(
              (center.latitude + y * step).clamp(-85, 85),
              (center.longitude + x * step).clamp(-180, 180),
            );
            setState(() => _selected = point);
            _map.move(point, _map.camera.zoom);
          },
  );
}
