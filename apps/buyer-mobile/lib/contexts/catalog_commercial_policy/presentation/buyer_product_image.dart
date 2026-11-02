import 'package:flutter/material.dart';

import 'buyer_catalog_image_registry.dart';

final class BuyerProductImage extends StatelessWidget {
  const BuyerProductImage({
    super.key,
    required this.name,
    this.url,
    this.fileName,
    this.height = 176,
  });
  final String name;
  final String? url;
  final String? fileName;
  final double height;

  @override
  Widget build(BuildContext context) {
    final assetPath = buyerCatalogImageAssetPath(fileName);
    final cacheHeight = (height * MediaQuery.devicePixelRatioOf(context))
        .ceil()
        .clamp(1, 768)
        .toInt();
    return SizedBox(
      width: double.infinity,
      height: height,
      child: ColoredBox(
        color: Theme.of(context).colorScheme.surface,
        child: assetPath == null
            ? _networkImage(context)
            : Image.asset(
                assetPath,
                cacheHeight: cacheHeight,
                fit: BoxFit.contain,
                semanticLabel: name,
                errorBuilder: (_, _, _) => _networkImage(context),
              ),
      ),
    );
  }

  Widget _networkImage(BuildContext context) => url == null
      ? _unavailable(context)
      : Image.network(
          url!,
          cacheHeight: (height * MediaQuery.devicePixelRatioOf(context))
              .ceil()
              .clamp(1, 768)
              .toInt(),
          fit: BoxFit.contain,
          semanticLabel: name,
          errorBuilder: (_, _, _) => _unavailable(context),
          loadingBuilder: (context, child, progress) => progress == null
              ? child
              : Center(
                  child: CircularProgressIndicator(
                    semanticsLabel: 'Cargando foto de $name',
                    value: progress.expectedTotalBytes == null
                        ? null
                        : progress.cumulativeBytesLoaded /
                              progress.expectedTotalBytes!,
                  ),
                ),
        );

  Widget _unavailable(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final scale = MediaQuery.textScalerOf(context).scale(16) / 16;
      final showLabel =
          constraints.maxWidth >= 140 * scale &&
          constraints.maxHeight >= 100 * scale;
      return Semantics(
        label: 'Foto no disponible de $name',
        image: true,
        child: ExcludeSemantics(
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.photo_outlined,
                  size: 32,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                if (showLabel) ...[
                  const SizedBox(height: 8),
                  Text(
                    'Foto no disponible',
                    style: Theme.of(context).textTheme.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                ],
              ],
            ),
          ),
        ),
      );
    },
  );
}

String buyerColdChainLabel(String value) => switch (value.toUpperCase()) {
  'REFRIGERATED' || 'CHILLED' => 'Mantener refrigerado',
  'FROZEN' => 'Mantener congelado',
  'AMBIENT' || 'NONE' => 'Conservar a temperatura ambiente',
  _ => value,
};
