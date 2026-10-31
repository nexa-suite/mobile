import 'package:flutter/material.dart';

import '../contexts/catalog_commercial_policy/application/catalog_repository.dart';
import '../contexts/catalog_commercial_policy/presentation/buyer_product_image.dart';
import '../contexts/catalog_commercial_policy/presentation/catalog_commercial_format.dart';
import '../core/presentation/nexa_buyer_palette.dart';
import '../core/presentation/nexa_action_surface.dart';

class BuyerCatalogPreview extends StatelessWidget {
  const BuyerCatalogPreview({
    super.key,
    required this.items,
    this.total,
    required this.onOpen,
    required this.onSelected,
    this.loading = false,
  });
  final List<CatalogItemProjection> items;
  final int? total;
  final bool loading;
  final VoidCallback onOpen;
  final ValueChanged<CatalogItemProjection> onSelected;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final largeText = MediaQuery.textScalerOf(context).scale(16) / 16 >= 1.3;
    final heading = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Tu catálogo',
          style: theme.textTheme.headlineLarge?.copyWith(color: Colors.white),
        ),
        const SizedBox(height: 6),
        Text(
          loading
              ? 'Actualizando productos…'
              : total == null
              ? 'Encuentra tu próxima compra'
              : '$total productos para elegir',
          style: theme.textTheme.bodySmall?.copyWith(
            color: Colors.white.withValues(alpha: .85),
          ),
        ),
        const SizedBox(height: 16),
        OutlinedButton.icon(
          onPressed: onOpen,
          style: OutlinedButton.styleFrom(
            backgroundColor: Colors.white,
            foregroundColor: NexaBuyerPalette.primary,
            minimumSize: const Size(48, 48),
            padding: const EdgeInsets.symmetric(horizontal: 12),
          ),
          icon: const Icon(Icons.arrow_forward, size: 18),
          label: const Text('Explorar'),
        ),
      ],
    );
    return Material(
      color: NexaBuyerPalette.primary,
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: largeText
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  heading,
                  if (items.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    Align(
                      alignment: Alignment.centerRight,
                      child: _ProductCollection(
                        items: items,
                        onSelected: onSelected,
                      ),
                    ),
                  ],
                ],
              )
            : Row(
                children: [
                  Expanded(child: heading),
                  if (items.isNotEmpty) ...[
                    const SizedBox(width: 8),
                    _ProductCollection(items: items, onSelected: onSelected),
                  ],
                ],
              ),
      ),
    );
  }
}

class _ProductCollection extends StatelessWidget {
  const _ProductCollection({required this.items, required this.onSelected});
  final List<CatalogItemProjection> items;
  final ValueChanged<CatalogItemProjection> onSelected;
  @override
  Widget build(BuildContext context) {
    final products = items.take(3).toList();
    return SizedBox(
      width: 152,
      height: 132,
      child: Stack(
        children: [
          _ProductCutout(
            item: products[0],
            width: 88,
            height: 116,
            onTap: () => onSelected(products[0]),
          ),
          if (products.length > 1)
            Positioned(
              top: 0,
              right: 0,
              child: Transform.rotate(
                angle: .06,
                child: _ProductCutout(
                  item: products[1],
                  width: 64,
                  height: 68,
                  onTap: () => onSelected(products[1]),
                ),
              ),
            ),
          if (products.length > 2)
            Positioned(
              right: 0,
              bottom: 0,
              child: Transform.rotate(
                angle: -.04,
                child: _ProductCutout(
                  item: products[2],
                  width: 64,
                  height: 68,
                  onTap: () => onSelected(products[2]),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _ProductCutout extends StatelessWidget {
  const _ProductCutout({
    required this.item,
    required this.width,
    required this.height,
    required this.onTap,
  });
  final CatalogItemProjection item;
  final double width;
  final double height;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: width,
    height: height,
    child: Tooltip(
      message: item.itemName,
      child: Semantics(
        label: 'Ver ${item.itemName}',
        button: true,
        child: NexaActionSurface(
          color: Colors.white,
          radius: 10,
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: ExcludeSemantics(
              child: BuyerProductImage(
                name: item.itemName,
                url: item.imageUrl,
                fileName: item.imageFileName,
                height: height - 12,
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class BuyerCategoryShortcuts extends StatelessWidget {
  const BuyerCategoryShortcuts({
    super.key,
    required this.items,
    required this.onSelected,
  });
  final List<CatalogFilterOptionProjection> items;
  final ValueChanged<CatalogFilterOptionProjection> onSelected;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final largeText = MediaQuery.textScalerOf(context).scale(16) / 16 >= 1.3;
      final categories = items.take(3).toList();
      final width = largeText
          ? constraints.maxWidth
          : (constraints.maxWidth - (categories.length - 1) * 8) /
                categories.length;
      return Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (var index = 0; index < categories.length; index++)
            SizedBox(
              width: width,
              child: NexaActionSurface(
                color: _categorySurface(categories[index].name),
                radius: 10,
                onTap: () => onSelected(categories[index]),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(minHeight: 56),
                  child: Padding(
                    padding: const EdgeInsets.all(10),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text(
                            buyerCatalogCategoryLabel(categories[index].name),
                            style: Theme.of(context).textTheme.labelMedium,
                          ),
                        ),
                        const SizedBox(width: 4),
                        const Icon(Icons.chevron_right, size: 16),
                      ],
                    ),
                  ),
                ),
              ),
            ),
        ],
      );
    },
  );
}

Color _categorySurface(String name) => switch (name.trim().toLowerCase()) {
  'butter' ||
  'mantequilla' ||
  'dairy' ||
  'lácteos' => NexaBuyerPalette.positiveSurface,
  'charcuterie' || 'charcutería' => NexaBuyerPalette.attentionSurface,
  'cheese' || 'quesos' => NexaBuyerPalette.primarySurface,
  _ => NexaBuyerPalette.cardSurface,
};
