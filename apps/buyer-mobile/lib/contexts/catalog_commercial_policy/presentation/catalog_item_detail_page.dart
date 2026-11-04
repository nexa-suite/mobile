import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../application/catalog_repository.dart';
import 'catalog_commercial_format.dart';
import 'buyer_product_image.dart';
import 'catalog_item_detail_view_model.dart';

final class CatalogItemDetailPage extends StatefulWidget {
  const CatalogItemDetailPage({super.key, this.favoriteActionBuilder});

  final Widget Function(CatalogItemProjection)? favoriteActionBuilder;

  @override
  State<CatalogItemDetailPage> createState() => _CatalogItemDetailPageState();
}

final class _CatalogItemDetailPageState extends State<CatalogItemDetailPage> {
  GoRouter? _router;
  String? _routePath;
  BuyerVisibleRoutePoller? _poller;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.maybeOf(context);
    if (router == null) return;
    final model = context.read<CatalogItemDetailViewModel>();
    final routePath = '/catalog/${Uri.encodeComponent(model.catalogItemId)}';
    if (identical(router, _router) && routePath == _routePath) return;
    _poller?.dispose();
    _router = router;
    _routePath = routePath;
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: routePath,
      onRefresh: model.refresh,
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: CatalogItemDetailViewModel.readPermission)
          .where((event) => event.type == 'resync-required')
          .map<void>((_) {}),
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final detail = context.watch<CatalogItemDetailViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('Detalle del producto')),
      body: SafeArea(top: false, child: _content(context, detail)),
    );
  }

  Widget _content(BuildContext context, CatalogItemDetailViewModel detail) {
    final item = detail.item;
    if (detail.status == CatalogItemDetailStatus.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (item == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.inventory_2_outlined,
                size: 36,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(height: 12),
              Text(
                detail.message ?? 'Este producto no está disponible.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: detail.load,
                icon: const Icon(Icons.refresh),
                label: const Text('Volver a consultar'),
              ),
            ],
          ),
        ),
      );
    }

    final permissions = context
        .read<BuyerAccessRepository>()
        .snapshot
        .currentContext
        ?.permissions;
    final canCreateRequest =
        permissions?.contains('buyer.sales.read') == true &&
        permissions?.contains('buyer.sales.write') == true;
    final sellableSkuId = item.sellableSkuId;
    final hasSellableSku =
        sellableSkuId != null &&
        RegExp(
          r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
        ).hasMatch(sellableSkuId);

    return RefreshIndicator(
      onRefresh: detail.refresh,
      child: NexaPageWidth(
        padding: EdgeInsets.zero,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final useColumns = constraints.maxWidth >= 720;
            final identity = _ProductIdentityCard(item: item);
            final commercialFacts = _CommercialFacts(item: item);

            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              children: [
                if (useColumns)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(flex: 5, child: identity),
                      const SizedBox(width: 12),
                      Expanded(flex: 4, child: commercialFacts),
                    ],
                  )
                else ...[
                  identity,
                  const SizedBox(height: 12),
                  commercialFacts,
                ],
                if (canCreateRequest && hasSellableSku) ...[
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => context.push(
                        '/catalog/${Uri.encodeComponent(item.catalogItemId)}/purchase-request',
                      ),
                      icon: const Icon(Icons.request_quote_outlined),
                      label: const Text('Crear solicitud de compra'),
                    ),
                  ),
                ] else if (canCreateRequest) ...[
                  const SizedBox(height: 12),
                  Text(
                    'Este producto no tiene un SKU de venta disponible para solicitar.',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
                const SizedBox(height: 12),
                _ProductDetailsCard(item: item),
                if (widget.favoriteActionBuilder != null) ...[
                  const SizedBox(height: 12),
                  widget.favoriteActionBuilder!(item),
                ],
                const SizedBox(height: 12),
                const NexaNotice(
                  'El precio y la disponibilidad pueden cambiar antes de confirmar un pedido.',
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

final class _ProductIdentityCard extends StatelessWidget {
  const _ProductIdentityCard({required this.item});

  final CatalogItemProjection item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final productName = item.itemName.trim().toLowerCase();
    final descriptors = {
      ?item.productFamilyName?.trim(),
      ?item.productVariantName?.trim(),
      ?item.presentation?.trim(),
    }.where((value) => value.isNotEmpty && value.toLowerCase() != productName);

    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          BuyerProductImage(
            name: item.itemName,
            url: item.imageUrl,
            fileName: item.imageFileName,
            height: 196,
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (item.brandName case final brand?) ...[
                  Text(
                    brand.toUpperCase(),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.primary,
                      letterSpacing: 1,
                    ),
                  ),
                  const SizedBox(height: 6),
                ],
                Text(item.itemName, style: theme.textTheme.titleLarge),
                if (item.categoryName case final category?) ...[
                  const SizedBox(height: 6),
                  Text(
                    category,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
                if (descriptors.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      for (final descriptor in descriptors)
                        Chip(label: Text(descriptor)),
                    ],
                  ),
                ],
                if (item.promotionLabel case final promotion?) ...[
                  const SizedBox(height: 12),
                  Chip(
                    avatar: Icon(
                      Icons.local_offer_outlined,
                      size: 18,
                      color: theme.colorScheme.primary,
                    ),
                    label: Text(promotion),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

final class _CommercialFacts extends StatelessWidget {
  const _CommercialFacts({required this.item});

  final CatalogItemProjection item;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final quantity = item.sellableAvailability;
    final updatedPrice = _asOfLabel(item.pricingAsOf);
    final updatedAvailability = _asOfLabel(item.availabilityAsOf);

    return Card.outlined(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Precio actual',
                        style: theme.textTheme.labelLarge?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        buyerCatalogPriceLabel(
                          item.price?.amount,
                          item.price?.currency,
                        ),
                        style: theme.textTheme.titleLarge,
                      ),
                      if (updatedPrice.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          updatedPrice,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                NexaStatusBadge(
                  item.availabilityStatus,
                  label: _availabilityLabel(item.availabilityStatus),
                ),
              ],
            ),
            if (quantity != null) ...[
              const SizedBox(height: 10),
              Text(
                buyerCatalogQuantityLabel(quantity, item.unitOfMeasure),
                style: theme.textTheme.titleMedium,
              ),
            ],
            if (updatedAvailability.isNotEmpty &&
                updatedAvailability != updatedPrice) ...[
              const SizedBox(height: 4),
              Text(
                updatedAvailability,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

final class _ProductDetailsCard extends StatelessWidget {
  const _ProductDetailsCard({required this.item});

  final CatalogItemProjection item;

  @override
  Widget build(BuildContext context) {
    final description = item.description?.trim();
    final facts = <(String, String, IconData)>[
      if (description != null && description.isNotEmpty)
        ('Descripción', description, Icons.notes_outlined),
      if (item.skuCode case final sku?) ('SKU', sku, Icons.qr_code_2_outlined),
      if (item.unitOfMeasure case final unit?)
        (
          'Unidad de venta',
          buyerCatalogUnitLabel(unit),
          Icons.straighten_outlined,
        ),
      if (item.packagingType case final packaging?)
        ('Empaque', packaging, Icons.inventory_2_outlined),
      if (item.coldChainRequirement case final requirement?)
        ('Conservación', requirement, Icons.ac_unit_outlined),
      if (item.netWeight != null || item.grossWeight != null)
        (
          'Peso',
          [
            if (item.netWeight case final weight?) 'Neto $weight g',
            if (item.grossWeight case final weight?) 'Bruto $weight g',
          ].join(' · '),
          Icons.scale_outlined,
        ),
    ];
    if (facts.isEmpty) return const SizedBox.shrink();

    return Card(
      margin: EdgeInsets.zero,
      child: ExpansionTile(
        title: const Text('Más información'),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          for (final (label, value, icon) in facts)
            NexaFactTile(label: label, value: value, icon: icon),
        ],
      ),
    );
  }
}

String _availabilityLabel(String status) => switch (status.toUpperCase()) {
  'AVAILABLE' => 'Disponible',
  'LOW' => 'Disponibilidad baja',
  'UNAVAILABLE' => 'No disponible',
  _ => 'Por confirmar',
};

String _asOfLabel(String? value) {
  if (value == null) return '';
  final date = DateTime.tryParse(value)?.toLocal();
  if (date == null) return '';
  final day = date.day.toString().padLeft(2, '0');
  final month = date.month.toString().padLeft(2, '0');
  return 'Actualizado $day/$month/${date.year}';
}
