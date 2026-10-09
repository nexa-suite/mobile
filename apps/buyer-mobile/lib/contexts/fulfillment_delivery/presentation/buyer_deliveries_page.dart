import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../application/buyer_deliveries_repository.dart';
import 'buyer_deliveries_view_model.dart';

final class BuyerDeliveriesPage extends StatefulWidget {
  const BuyerDeliveriesPage({super.key});

  @override
  State<BuyerDeliveriesPage> createState() => _BuyerDeliveriesPageState();
}

final class _BuyerDeliveriesPageState extends State<BuyerDeliveriesPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerDeliveriesViewModel>().refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final deliveries = context.watch<BuyerDeliveriesViewModel>();
    if (deliveries.status == BuyerDeliveriesStatus.loading &&
        deliveries.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (deliveries.status != BuyerDeliveriesStatus.current &&
        deliveries.items.isEmpty) {
      return _DeliveryMessage(
        message:
            deliveries.message ??
            (deliveries.status == BuyerDeliveriesStatus.current
                ? 'Todavía no hay entregas registradas.'
                : 'No se pudo consultar el seguimiento de entregas.'),
        showRefresh:
            deliveries.status != BuyerDeliveriesStatus.permissionDenied,
        onRefresh: deliveries.refresh,
      );
    }
    if (deliveries.items.isEmpty) {
      return _DeliveryMessage(
        message: 'Todavía no hay entregas registradas.',
        showRefresh: true,
        onRefresh: deliveries.refresh,
      );
    }

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 8, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'Mis entregas',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              IconButton(
                tooltip: 'Actualizar entregas',
                onPressed: deliveries.status == BuyerDeliveriesStatus.loading
                    ? null
                    : deliveries.refresh,
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
        ),
        if (deliveries.message != null)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            child: Semantics(
              liveRegion: true,
              child: Text(deliveries.message!),
            ),
          ),
        Expanded(
          child: RefreshIndicator(
            onRefresh: deliveries.refresh,
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 20),
              itemCount: deliveries.items.length + 1,
              itemBuilder: (context, index) {
                if (index == deliveries.items.length) {
                  return _DeliveryPageControls(
                    page: deliveries.page,
                    size: BuyerDeliveriesViewModel.pageSize,
                    total: deliveries.total,
                    onPrevious: deliveries.previousPage,
                    onNext: deliveries.nextPage,
                  );
                }
                final delivery = deliveries.items[index];
                return _DeliveryCard(
                  delivery: delivery,
                  onTap: () => context.push(
                    '/deliveries/${Uri.encodeComponent(delivery.id)}',
                  ),
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}

final class _DeliveryCard extends StatelessWidget {
  const _DeliveryCard({required this.delivery, required this.onTap});

  final BuyerDeliveryProjection delivery;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      title: Text(delivery.dispatchNumber),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Pedido: ${delivery.salesOrderNumber ?? 'No disponible'}'),
            Text('Estado: ${_statusLabel(delivery.status)}'),
            Text('ETA: ${_dateTimeLabel(delivery.eta) ?? 'No programada'}'),
          ],
        ),
      ),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}

final class _DeliveryPageControls extends StatelessWidget {
  const _DeliveryPageControls({
    required this.page,
    required this.size,
    required this.total,
    required this.onPrevious,
    required this.onNext,
  });

  final int page;
  final int size;
  final int total;
  final Future<void> Function() onPrevious;
  final Future<void> Function() onNext;

  @override
  Widget build(BuildContext context) {
    final totalPages = total == 0 ? 1 : ((total - 1) ~/ size) + 1;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          IconButton(
            tooltip: 'Entregas anteriores',
            onPressed: page > 0 ? onPrevious : null,
            icon: const Icon(Icons.chevron_left),
          ),
          Flexible(child: Text('${page + 1} de $totalPages · $total entregas')),
          IconButton(
            tooltip: 'Entregas siguientes',
            onPressed: (page + 1) * size < total ? onNext : null,
            icon: const Icon(Icons.chevron_right),
          ),
        ],
      ),
    );
  }
}

final class _DeliveryMessage extends StatelessWidget {
  const _DeliveryMessage({
    required this.message,
    this.showRefresh = false,
    this.onRefresh,
  });

  final String message;
  final bool showRefresh;
  final Future<void> Function()? onRefresh;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          if (showRefresh && onRefresh != null) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: onRefresh,
              icon: const Icon(Icons.refresh),
              label: const Text('Actualizar'),
            ),
          ],
        ],
      ),
    ),
  );
}

String _statusLabel(String value) => switch (value) {
  'PREPARING_DELIVERY' => 'Preparando entrega',
  'DELIVERY_SCHEDULED' => 'Entrega programada',
  'IN_TRANSIT' => 'En tránsito',
  'DELIVERY_REVIEW' => 'Revisión de entrega',
  'DELIVERY_RESCHEDULED' => 'Entrega reprogramada',
  'PARTIAL' => 'Entrega parcial',
  'DELIVERED' => 'Entregada',
  'DELIVERY_CANCELLED' => 'Entrega cancelada',
  'UNKNOWN' => 'Estado no disponible',
  _ => value.replaceAll('_', ' ').toLowerCase(),
};

String? _dateTimeLabel(DateTime? value) {
  if (value == null) return null;
  final local = value.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  final hour = local.hour.toString().padLeft(2, '0');
  final minute = local.minute.toString().padLeft(2, '0');
  return '$day/$month/${local.year} $hour:$minute';
}
