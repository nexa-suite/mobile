import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../tenant_access_governance/application/buyer_access_repository.dart';
import 'catalog_item_detail_view_model.dart';

final class CatalogItemDetailPage extends StatelessWidget {
  const CatalogItemDetailPage({super.key});

  @override
  Widget build(BuildContext context) {
    final detail = context.watch<CatalogItemDetailViewModel>();
    final item = detail.item;
    return Scaffold(
      appBar: AppBar(title: Text(item?.itemName ?? 'Detalle del producto')),
      body: _content(context, detail),
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
              Text(
                detail.message ?? 'Este producto no está disponible.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: detail.load,
                child: const Text('Volver a consultar'),
              ),
            ],
          ),
        ),
      );
    }

    final metadata = <String>[
      ?item.brandName,
      ?item.productFamilyName,
      ?item.productVariantName,
      ?item.presentation,
      if (item.skuCode case final value?) 'SKU $value',
    ];
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
      onRefresh: detail.load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.itemName,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  if (metadata.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(metadata.join(' · ')),
                  ],
                  if (item.description case final description?) ...[
                    const SizedBox(height: 12),
                    Text(description),
                  ],
                  if (item.promotionLabel case final promotion?) ...[
                    const SizedBox(height: 12),
                    Chip(label: Text(promotion)),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          _InformationCard(
            title: 'Precio actual',
            value: item.price == null
                ? 'Sin precio disponible'
                : '${item.price!.amount} ${item.price!.currency}',
            detail: _asOfLabel(item.pricingAsOf),
          ),
          _InformationCard(
            title: 'Disponibilidad actual',
            value: _availabilityLabel(item.availabilityStatus),
            detail: [
              if (item.sellableAvailability case final quantity?)
                '${quantity.toString()} ${item.unitOfMeasure ?? ''}'.trim(),
              _asOfLabel(item.availabilityAsOf),
            ].where((value) => value.isNotEmpty).join(' · '),
          ),
          if (item.coldChainRequirement case final requirement?)
            _InformationCard(title: 'Conservación', value: requirement),
          if (item.packagingType case final packaging?)
            _InformationCard(title: 'Presentación', value: packaging),
          if (item.netWeight != null || item.grossWeight != null)
            _InformationCard(
              title: 'Peso',
              value: [
                if (item.netWeight case final weight?) 'Neto $weight g',
                if (item.grossWeight case final weight?) 'Bruto $weight g',
              ].join(' · '),
            ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'El precio y la disponibilidad pueden cambiar antes de confirmar un pedido.',
              textAlign: TextAlign.center,
            ),
          ),
          if (canCreateRequest && hasSellableSku)
            FilledButton.icon(
              onPressed: () => context.push(
                '/catalog/${Uri.encodeComponent(item.catalogItemId)}/purchase-request',
              ),
              icon: const Icon(Icons.request_quote_outlined),
              label: const Text('Crear solicitud de compra'),
            )
          else if (canCreateRequest)
            const Text(
              'Este producto no tiene un SKU de venta disponible para solicitar.',
              textAlign: TextAlign.center,
            ),
        ],
      ),
    );
  }
}

final class _InformationCard extends StatelessWidget {
  const _InformationCard({
    required this.title,
    required this.value,
    this.detail,
  });

  final String title;
  final String value;
  final String? detail;

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 8),
    child: ListTile(
      title: Text(title),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(value),
            if (detail != null && detail!.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(detail!, style: Theme.of(context).textTheme.bodySmall),
            ],
          ],
        ),
      ),
    ),
  );
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
