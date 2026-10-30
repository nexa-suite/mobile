import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../application/buyer_order_history_repository.dart';
import 'buyer_order_history_view_model.dart';

class BuyerOrderHistoryCard extends StatefulWidget {
  const BuyerOrderHistoryCard({super.key, required this.orderId});
  final String orderId;
  @override
  State<BuyerOrderHistoryCard> createState() => _BuyerOrderHistoryCardState();
}

class _BuyerOrderHistoryCardState extends State<BuyerOrderHistoryCard> {
  late BuyerOrderHistoryViewModel _model;
  BuyerVisibleRoutePoller? _poller;
  @override
  void initState() {
    super.initState();
    _createModel();
  }

  void _createModel() {
    _model = BuyerOrderHistoryViewModel(
      context.read<BuyerOrderHistoryRepository>(),
      context.read<BuyerAccessRepository>(),
      widget.orderId,
    );
    final currentModel = _model;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && identical(_model, currentModel)) currentModel.load();
    });
  }

  @override
  void didUpdateWidget(covariant BuyerOrderHistoryCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.orderId == widget.orderId) return;
    _poller?.dispose();
    _poller = null;
    _model.dispose();
    _createModel();
    _attachPoller();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _attachPoller();
  }

  void _attachPoller() {
    _poller ??= BuyerVisibleRoutePoller(
      router: GoRouter.of(context),
      routePath: '/orders/${Uri.encodeComponent(widget.orderId)}',
      onRefresh: _model.load,
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: 'buyer.order.read')
          .map<void>((_) {}),
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    _model.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _model,
    builder: (context, _) => Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Historia del pedido',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            if (_model.busy) const LinearProgressIndicator(),
            if (_model.message case final message?) ...[
              Text(message),
              TextButton(
                onPressed: _model.busy ? null : _model.load,
                child: const Text('Actualizar historial'),
              ),
            ],
            if (!_model.busy && _model.events.isEmpty && _model.message == null)
              const Text('Aún no hay cambios registrados.'),
            for (final event in _model.events)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      Icons.check_circle_outline,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (event.toStatus != null)
                            NexaStatusBadge(event.toStatus!)
                          else
                            Text(_eventLabel(event.type)),
                          const SizedBox(height: 4),
                          Text(
                            _date(event.occurredAt),
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    ),
  );
  String _eventLabel(String type) => switch (type.toUpperCase()) {
    'CREATED' || 'SALES_ORDER_CREATED' => 'Pedido creado',
    'CONFIRMED' || 'SALES_ORDER_CONFIRMED' => 'Pedido confirmado',
    'CANCELLED' || 'SALES_ORDER_CANCELLED' => 'Pedido cancelado',
    _ => 'Actualización del pedido',
  };
  String _date(DateTime time) {
    final date = time.toLocal();
    return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year} · ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }
}
