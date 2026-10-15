import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/buyer_orders_repository.dart';
import 'buyer_orders_view_model.dart';

final class BuyerOrderDetailPage extends StatelessWidget {
  const BuyerOrderDetailPage({super.key});

  @override
  Widget build(BuildContext context) {
    final detail = context.watch<BuyerOrderDetailViewModel>();
    final order = detail.order;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          order == null ? 'Detalle del pedido' : 'Pedido ${order.number}',
        ),
      ),
      body: SafeArea(top: false, child: _content(context, detail, order)),
    );
  }

  Widget _content(
    BuildContext context,
    BuyerOrderDetailViewModel detail,
    BuyerOrderProjection? order,
  ) {
    if (detail.status == BuyerOrderDetailStatus.loading ||
        (detail.status == BuyerOrderDetailStatus.current && order == null)) {
      return const Center(child: CircularProgressIndicator());
    }
    if (order == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                detail.message ?? 'Este pedido no está disponible.',
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
    return RefreshIndicator(
      onRefresh: detail.load,
      child: NexaPageWidth(
        padding: EdgeInsets.zero,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          children: [
            NexaPageWidth(
              padding: EdgeInsets.zero,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  NexaPageIntro(
                    title: 'Pedido ${order.number}',
                    description: 'Información actual de este pedido.',
                    icon: Icons.receipt_long_outlined,
                  ),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          NexaStatusBadge(order.status),
                          if (order.createdAt != null)
                            NexaFactTile(
                              label: 'Creado',
                              value: _detailDate(order.createdAt!),
                            ),
                          if (order.requestedDeliveryDate != null)
                            NexaFactTile(
                              label: 'Entrega solicitada',
                              value: order.requestedDeliveryDate!,
                            ),
                          if (order.total != null && order.currency != null)
                            NexaFactTile(
                              label: 'Total',
                              value: '${order.total} ${order.currency}',
                              emphasis: true,
                            ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    'Productos',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  for (final line in order.lines) _OrderLineCard(line: line),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

final class _OrderLineCard extends StatelessWidget {
  const _OrderLineCard({required this.line});

  final BuyerOrderLineProjection line;

  @override
  Widget build(BuildContext context) {
    final subtitleParts = <String>[
      '${line.quantity} ${line.unit}',
      if (line.presentation?.isNotEmpty ?? false) line.presentation!,
    ];
    final amount = line.lineSubtotal;
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        title: Text(line.itemName),
        subtitle: Text(subtitleParts.join(' · ')),
        trailing: amount == null
            ? null
            : Text(
                line.unitPriceCurrency == null
                    ? '$amount'
                    : '$amount ${line.unitPriceCurrency}',
              ),
      ),
    );
  }
}

String _detailDate(String value) {
  final date = DateTime.tryParse(value)?.toLocal();
  if (date == null) return 'Fecha registrada';
  final day = date.day.toString().padLeft(2, '0');
  final month = date.month.toString().padLeft(2, '0');
  return '$day/$month/${date.year}';
}
