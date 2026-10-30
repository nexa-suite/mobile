import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/presentation/nexa_display_format.dart';
import '../../../core/presentation/nexa_buyer_palette.dart';
import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../application/buyer_orders_repository.dart';
import 'buyer_orders_view_model.dart';

const _orderChangeEvents = {
  'sales.sales-order.confirmed',
  'sales.sales-order.created',
  'resync-required',
};

final class BuyerOrdersPage extends StatefulWidget {
  const BuyerOrdersPage({super.key});

  @override
  State<BuyerOrdersPage> createState() => _BuyerOrdersPageState();
}

final class _BuyerOrdersPageState extends State<BuyerOrdersPage> {
  GoRouter? _router;
  BuyerVisibleRoutePoller? _poller;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.of(context);
    if (identical(router, _router)) return;
    _poller?.dispose();
    _router = router;
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: '/orders',
      onRefresh: () => context.read<BuyerOrdersViewModel>().refresh(),
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: BuyerOrdersViewModel.readPermission)
          .where((event) => _orderChangeEvents.contains(event.type))
          .map<void>((_) {}),
    );
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerOrdersViewModel>().refresh();
    });
  }

  @override
  void dispose() {
    _poller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final orders = context.watch<BuyerOrdersViewModel>();
    if (orders.status == BuyerOrdersLoadStatus.loading &&
        orders.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if ((orders.status == BuyerOrdersLoadStatus.unavailable ||
            orders.status == BuyerOrdersLoadStatus.permissionDenied) &&
        orders.items.isEmpty) {
      return _OrdersEmptyState(
        message: orders.message ?? 'No se pudieron consultar tus pedidos.',
        action: orders.status == BuyerOrdersLoadStatus.permissionDenied
            ? null
            : 'Reintentar',
        onAction: orders.status == BuyerOrdersLoadStatus.permissionDenied
            ? null
            : orders.refresh,
      );
    }
    return NexaPageWidth(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Card.outlined(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(4, 0, 4, 12),
                      child: Row(
                        children: [
                          const Icon(
                            Icons.shopping_bag_outlined,
                            color: NexaBuyerPalette.primary,
                            size: 20,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'Pedidos',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const Spacer(),
                          Text(
                            '${orders.total}',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ],
                      ),
                    ),
                    SegmentedButton<BuyerOrdersBucket>(
                      segments: const [
                        ButtonSegment(
                          value: BuyerOrdersBucket.active,
                          label: Text('Activos'),
                        ),
                        ButtonSegment(
                          value: BuyerOrdersBucket.history,
                          label: Text('Historial'),
                        ),
                      ],
                      selected: {orders.bucket},
                      onSelectionChanged: (selected) =>
                          unawaited(orders.selectBucket(selected.first)),
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (orders.status == BuyerOrdersLoadStatus.loading &&
              orders.items.isNotEmpty)
            const LinearProgressIndicator(),
          if (orders.status == BuyerOrdersLoadStatus.unavailable &&
              orders.items.isNotEmpty &&
              orders.message != null)
            MaterialBanner(
              content: Text(orders.message!),
              actions: [
                TextButton(
                  onPressed: orders.refresh,
                  child: const Text('Reintentar'),
                ),
              ],
            ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: orders.refresh,
              child: ListView.builder(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
                itemCount: (orders.items.isEmpty ? 1 : orders.items.length) + 1,
                itemBuilder: (context, index) {
                  final contentCount = orders.items.isEmpty
                      ? 1
                      : orders.items.length;
                  if (index == contentCount) {
                    return _OrdersPageControls(
                      page: orders.page,
                      size: orders.size,
                      total: orders.total,
                      onPrevious: orders.previousPage,
                      onNext: orders.nextPage,
                    );
                  }
                  if (orders.items.isEmpty) {
                    return Padding(
                      padding: const EdgeInsets.symmetric(vertical: 24),
                      child: Text(
                        orders.bucket == BuyerOrdersBucket.active
                            ? 'No tienes pedidos activos.'
                            : 'Todavía no tienes historial de pedidos.',
                        textAlign: TextAlign.center,
                      ),
                    );
                  }
                  final order = orders.items[index];
                  return NexaPageWidth(
                    padding: EdgeInsets.zero,
                    child: _OrderCard(
                      order: order,
                      onTap: () => context.push(
                        '/orders/${Uri.encodeComponent(order.id)}',
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

final class _OrderCard extends StatelessWidget {
  const _OrderCard({required this.order, required this.onTap});

  final BuyerOrderProjection order;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final total = order.total == null || order.currency == null
        ? null
        : nexaMoneyLabel(order.total.toString(), order.currency);
    return Card.outlined(
      margin: const EdgeInsets.only(bottom: 12),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: NexaBuyerPalette.attentionSurface,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: const Icon(
                      Icons.inventory_2_outlined,
                      color: NexaBuyerPalette.attention,
                      size: 22,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: theme.colorScheme.surfaceContainer,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          order.number,
                          style: theme.textTheme.labelMedium,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  const Icon(
                    Icons.chevron_right,
                    color: NexaBuyerPalette.muted,
                    size: 20,
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 12,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  NexaStatusBadge(order.status),
                  if (order.createdAt != null)
                    Text(
                      _dateLabel(order.createdAt!),
                      style: theme.textTheme.bodySmall,
                    ),
                ],
              ),
              if (total != null) ...[
                const SizedBox(height: 14),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.baseline,
                  textBaseline: TextBaseline.alphabetic,
                  children: [
                    Text('Total', style: theme.textTheme.bodySmall),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        total,
                        textAlign: TextAlign.end,
                        style: theme.textTheme.titleLarge,
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
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

String _dateLabel(String value) {
  final date = DateTime.tryParse(value)?.toLocal();
  if (date == null) return 'Fecha registrada';
  final day = date.day.toString().padLeft(2, '0');
  final month = date.month.toString().padLeft(2, '0');
  return '$day/$month/${date.year}';
}
