import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'buyer_deliveries_view_model.dart';

final class BuyerDeliveryDetailPage extends StatelessWidget {
  const BuyerDeliveryDetailPage({super.key});

  @override
  Widget build(BuildContext context) {
    final detail = context.watch<BuyerDeliveryDetailViewModel>();
    if (detail.status == BuyerDeliveryDetailStatus.loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    final delivery = detail.delivery;
    if (delivery == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Seguimiento de entrega')),
        body: _DeliveryDetailMessage(
          message: detail.message ?? 'Esta entrega no está disponible.',
          showRefresh:
              detail.status != BuyerDeliveryDetailStatus.permissionDenied,
          onRefresh: detail.refresh,
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text('Pedido ${delivery.salesOrderNumber}')),
      body: RefreshIndicator(
        onRefresh: detail.refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          children: [
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: detail.refresh,
                icon: const Icon(Icons.refresh),
                label: const Text('Actualizar'),
              ),
            ),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Estado de entrega',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(_statusLabel(delivery.status)),
                    const SizedBox(height: 12),
                    _DetailRow(
                      label: 'Pedido',
                      value: delivery.salesOrderNumber,
                    ),
                    if (delivery.destination != null)
                      _DetailRow(
                        label: 'Destino',
                        value: delivery.destination!,
                      ),
                    _DetailRow(
                      label: 'Programada',
                      value:
                          _dateTimeLabel(delivery.scheduledAt) ?? 'Sin fecha',
                    ),
                    _DetailRow(
                      label: 'Despachada',
                      value:
                          _dateTimeLabel(delivery.dispatchedAt) ??
                          'No disponible',
                    ),
                    _DetailRow(
                      label: 'Entregada',
                      value:
                          _dateTimeLabel(delivery.deliveredAt) ??
                          'No disponible',
                    ),
                    _DetailRow(
                      label: 'Comprobante de entrega',
                      value: delivery.proofOfDeliveryStatus == null
                          ? 'No disponible'
                          : _statusLabel(delivery.proofOfDeliveryStatus!),
                    ),
                    _DetailRow(
                      label: 'Actualización',
                      value: _dateTimeLabel(delivery.updatedAt)!,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Text(
              'Historial de entrega',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            if (detail.events.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text('Todavía no hay eventos de entrega disponibles.'),
              ),
            for (final event in detail.events)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.circle, size: 10),
                title: Text(_statusLabel(event.type)),
                subtitle: Text(_dateTimeLabel(event.occurredAt)!),
              ),
          ],
        ),
      ),
    );
  }
}

final class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 140,
          child: Text(label, style: Theme.of(context).textTheme.labelLarge),
        ),
        Expanded(child: Text(value)),
      ],
    ),
  );
}

final class _DeliveryDetailMessage extends StatelessWidget {
  const _DeliveryDetailMessage({
    required this.message,
    required this.showRefresh,
    required this.onRefresh,
  });

  final String message;
  final bool showRefresh;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          if (showRefresh) ...[
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

String _statusLabel(String value) => value
    .split('_')
    .map(
      (part) => part.isEmpty
          ? part
          : '${part[0].toUpperCase()}${part.substring(1).toLowerCase()}',
    )
    .join(' ');

String? _dateTimeLabel(DateTime? value) {
  if (value == null) return null;
  final local = value.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  final hour = local.hour.toString().padLeft(2, '0');
  final minute = local.minute.toString().padLeft(2, '0');
  return '$day/$month/${local.year} $hour:$minute';
}
