import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../application/buyer_orders_repository.dart';
import 'buyer_orders_view_model.dart';

final class BuyerOrdersPage extends StatefulWidget {
  const BuyerOrdersPage({super.key});

  @override
  State<BuyerOrdersPage> createState() => _BuyerOrdersPageState();
}

final class _BuyerOrdersPageState extends State<BuyerOrdersPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerOrdersViewModel>().refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final orders = context.watch<BuyerOrdersViewModel>();
    if (orders.status == BuyerOrdersLoadStatus.loading &&
        orders.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (orders.status == BuyerOrdersLoadStatus.unavailable &&
        orders.items.isEmpty) {
      return _OrdersEmptyState(
        message: orders.message ?? 'No se pudieron consultar tus pedidos.',
        action: 'Reintentar',
        onAction: orders.refresh,
      );
    }
    if (orders.status == BuyerOrdersLoadStatus.current &&
        orders.items.isEmpty) {
      return const _OrdersEmptyState(message: 'Todavía no tienes pedidos.');
    }

    return RefreshIndicator(
      onRefresh: orders.refresh,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
        itemCount: orders.items.length + 1,
        itemBuilder: (context, index) {
          if (index == orders.items.length) {
            return _OrdersPageControls(
              page: orders.page,
              size: orders.size,
              total: orders.total,
              onPrevious: orders.previousPage,
              onNext: orders.nextPage,
            );
          }
          final order = orders.items[index];
          return _OrderCard(
            order: order,
            onTap: () =>
                context.push('/orders/${Uri.encodeComponent(order.id)}'),
          );
        },
      ),
    );
  }
}

final class _OrderCard extends StatelessWidget {
  const _OrderCard({required this.order, required this.onTap});

  final BuyerOrderProjection order;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      title: Text('Pedido ${order.number}'),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Estado: ${_statusLabel(order.status)}'),
            if (order.createdAt != null) Text(_dateLabel(order.createdAt!)),
            if (order.total != null && order.currency != null)
              Text('Total ${order.total} ${order.currency}'),
          ],
        ),
      ),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}

final class _OrdersPageControls extends StatelessWidget {
  const _OrdersPageControls({
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
            tooltip: 'Página anterior',
            onPressed: page > 0 ? onPrevious : null,
            icon: const Icon(Icons.chevron_left),
          ),
          Flexible(child: Text('${page + 1} de $totalPages · $total pedidos')),
          IconButton(
            tooltip: 'Página siguiente',
            onPressed: (page + 1) * size < total ? onNext : null,
            icon: const Icon(Icons.chevron_right),
          ),
        ],
      ),
    );
  }
}

final class _OrdersEmptyState extends StatelessWidget {
  const _OrdersEmptyState({required this.message, this.action, this.onAction});

  final String message;
  final String? action;
  final Future<void> Function()? onAction;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          if (action != null && onAction != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onAction, child: Text(action!)),
          ],
        ],
      ),
    ),
  );
}

String _statusLabel(String status) => status.replaceAll('_', ' ').toLowerCase();

String _dateLabel(String value) {
  final date = DateTime.tryParse(value)?.toLocal();
  if (date == null) return 'Fecha registrada';
  final day = date.day.toString().padLeft(2, '0');
  final month = date.month.toString().padLeft(2, '0');
  return 'Creado el $day/$month/${date.year}';
}
