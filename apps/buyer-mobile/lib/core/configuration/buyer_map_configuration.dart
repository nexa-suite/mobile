/// Tile source used to render the Buyer delivery map.
///
/// Debug builds use OpenStreetMap tiles locally. Release builds need an
/// explicitly configured tile source and its required attribution.
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
    bool isDebugBuild = false,
  }) {
    final configuredTiles = tileUrlTemplate.trim();
    final tiles = configuredTiles.isNotEmpty
        ? configuredTiles
        : isDebugBuild
        ? openStreetMapTileUrl
        : '';
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
