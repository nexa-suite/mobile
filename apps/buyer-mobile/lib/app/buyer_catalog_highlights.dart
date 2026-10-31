import 'package:flutter/material.dart';

import '../contexts/catalog_commercial_policy/application/catalog_repository.dart';
import '../contexts/catalog_commercial_policy/presentation/buyer_product_image.dart';
import '../core/presentation/nexa_buyer_palette.dart';
import '../core/presentation/nexa_action_surface.dart';

class BuyerCatalogHighlights extends StatelessWidget {
  const BuyerCatalogHighlights({
    super.key,
    required this.items,
    required this.onSelected,
  });
  final List<CatalogItemProjection> items;
  final ValueChanged<CatalogItemProjection> onSelected;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('Del catálogo', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 12),
      LayoutBuilder(
        builder: (context, constraints) {
          final largeText =
              MediaQuery.textScalerOf(context).scale(16) / 16 >= 1.3;
          final wide = constraints.maxWidth >= 700 && !largeText;
          final cards = [
            for (final item in items)
              Semantics(
                label: [
                  item.brandName,
                  item.itemName,
                  item.presentation,
                ].whereType<String>().join(', '),
                button: true,
                excludeSemantics: true,
                onTap: () => onSelected(item),
                child: NexaActionSurface(
                  onTap: () => onSelected(item),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(
                      children: [
                        SizedBox(
                          width: 72,
                          child: BuyerProductImage(
                            name: item.itemName,
                            url: item.imageUrl,
                            fileName: item.imageFileName,
                            height: 88,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (item.brandName != null)
                                Text(
                                  item.brandName!,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              const SizedBox(height: 4),
                              Text(
                                item.itemName,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.titleSmall,
                              ),
                              if (item.presentation != null) ...[
                                const SizedBox(height: 4),
                                Text(
                                  item.presentation!,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ],
                          ),
                        ),
                        const Icon(
                          Icons.chevron_right,
                          size: 18,
                          color: NexaBuyerPalette.primary,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ];
          return SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var i = 0; i < cards.length; i++) ...[
                    if (i > 0) const SizedBox(width: 12),
                    SizedBox(
                      width: wide
                          ? (constraints.maxWidth - (cards.length - 1) * 12) /
                                cards.length
                          : (largeText ? 320.0 : 272.0).clamp(
                              0.0,
                              constraints.maxWidth,
                            ),
                      child: cards[i],
                    ),
                  ],
                ],
              ),
            ),
          );
        },
      ),
    ],
  );
}
