/// Tile source used to render the Buyer delivery map.
///
/// OpenStreetMap's public raster tiles are the local default. Deployments can
/// replace them with an approved HTTPS tile source and its required attribution
/// through build configuration.
final class BuyerMapConfiguration {
  const BuyerMapConfiguration({
    required this.tileUrlTemplate,
    required this.attribution,
  });

  static const openStreetMapTileUrl =
      'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  static const openStreetMapAttribution = '© OpenStreetMap contributors';

  factory BuyerMapConfiguration.resolve({
    required String tileUrlTemplate,
    required String attribution,
  }) {
    final configuredTiles = tileUrlTemplate.trim();
    final tiles = configuredTiles.isEmpty
        ? openStreetMapTileUrl
        : configuredTiles;
    final configuredAttribution = attribution.trim();
    final mapAttribution = configuredAttribution.isNotEmpty
        ? configuredAttribution
        : tiles == openStreetMapTileUrl
        ? openStreetMapAttribution
        : '';

    return BuyerMapConfiguration(
      tileUrlTemplate: tiles,
      attribution: mapAttribution,
    );
  }

  final String tileUrlTemplate;
  final String attribution;
}
