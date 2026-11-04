import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/presentation/nexa_display_format.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../application/buyer_purchase_workspace_repository.dart';
import 'buyer_requests_view_model.dart';

final class BuyerRequestDetailPage extends StatefulWidget {
  const BuyerRequestDetailPage({super.key});

  @override
  State<BuyerRequestDetailPage> createState() => _BuyerRequestDetailPageState();
}

final class _BuyerRequestDetailPageState extends State<BuyerRequestDetailPage> {
  GoRouter? _router;
  String? _routePath;
  BuyerVisibleRoutePoller? _poller;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.maybeOf(context);
    if (router == null) return;
    final model = context.read<BuyerRequestDetailViewModel>();
    final routePath = '/requests/${Uri.encodeComponent(model.id)}';
    if (identical(router, _router) && routePath == _routePath) return;
    _poller?.dispose();
    _router = router;
    _routePath = routePath;
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: routePath,
      onRefresh: () => context.read<BuyerRequestDetailViewModel>().load(),
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: 'buyer.sales.read')
          .where((event) => _requestChangeEvents.contains(event.type))
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
    final model = context.watch<BuyerRequestDetailViewModel>();
    final request = model.request;
    return Scaffold(
      appBar: AppBar(
        title: Text(request?.code ?? 'Solicitud de compra'),
        actions: [
          IconButton(
            tooltip: 'Actualizar solicitud',
            onPressed: model.busy ? null : model.load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: request == null
            ? model.busy
                  ? const Center(child: CircularProgressIndicator())
                  : NexaEmptyState(
                      title: model.message ?? 'Solicitud no disponible',
                      icon: Icons.shopping_bag_outlined,
                      actionLabel: 'Consultar estado',
                      onAction: model.load,
                    )
            : RefreshIndicator(
                onRefresh: model.load,
                child: NexaPageWidth(
                  padding: EdgeInsets.zero,
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: [
                      NexaRecordCard(
                        title: request.code,
                        status: request.status,
                        icon: Icons.shopping_bag_outlined,
                        subtitle: 'Tu proveedor revisa la solicitud antes de convertirla en pedido.',
                        footer: request.expiresAt == null
                            ? null
                            : Text('Vigencia: ${request.expiresAt}'),
                      ),
                      if (model.busy) const LinearProgressIndicator(),
                      if (model.message != null) ...[
                        NexaNotice(model.message!),
                        const SizedBox(height: 16),
                      ],
                      _TermsCard(
                        title: 'Condiciones de tu solicitud',
                        terms: request.terms,
                      ),
                      if (request.reviewNote != null) ...[
                        const SizedBox(height: 12),
                        NexaNotice(request.reviewNote!),
                      ],
                      if (model.proposal case final proposal?) ...[
                        const SizedBox(height: 24),
                        Text(
                          'Cambios propuestos',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 8),
                        NexaNotice(proposal.reason),
                        const SizedBox(height: 12),
                        _TermsCard(title: 'Antes', terms: proposal.original),
                        const SizedBox(height: 12),
                        _TermsCard(
                          title: 'Propuesta del proveedor',
                          terms: proposal.proposed,
                        ),
                        if (model.canDecide) ...[
                          const SizedBox(height: 16),
                          FilledButton.icon(
                            onPressed: () => _confirm(
                              context,
                              model,
                              BuyerRequestDecision.acceptChange,
                            ),
                            icon: const Icon(Icons.check),
                            label: const Text('Aceptar cambios'),
                          ),
                          const SizedBox(height: 8),
                          OutlinedButton(
                            onPressed: () => _confirm(
                              context,
                              model,
                              BuyerRequestDecision.rejectChange,
                            ),
                            child: const Text('Rechazar cambios'),
                          ),
                        ],
                      ],
                      const SizedBox(height: 24),
                      Text(
                        'Historial de la solicitud',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 12),
                      if (model.eventsMessage != null)
                        NexaNotice(model.eventsMessage!),
                      if (model.events.isEmpty && model.eventsMessage == null)
                        const Text('No hay eventos disponibles.'),
                      for (final event in model.events)
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          leading: Icon(
                            Icons.history,
                            color: Theme.of(context).colorScheme.primary,
                          ),
                          title: Text(
                            event.toStatus == null
                                ? event.type.replaceAll('_', ' ')
                                : nexaStatusLabel(event.toStatus!),
                          ),
                          subtitle: Text(
                            event.occurredAt
                                    ?.toLocal()
                                    .toString()
                                    .split('.')
                                    .first ??
                                'Fecha no disponible',
                          ),
                        ),
                      if (model.canWithdraw) ...[
                        const SizedBox(height: 24),
                        OutlinedButton.icon(
                          onPressed: () => _confirm(
                            context,
                            model,
                            BuyerRequestDecision.withdraw,
                          ),
                          icon: const Icon(Icons.undo),
                          label: const Text('Retirar solicitud'),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
      ),
    );
  }

  Future<void> _confirm(
    BuildContext context,
    BuyerRequestDetailViewModel model,
    BuyerRequestDecision decision,
  ) async {
    final title = switch (decision) {
      BuyerRequestDecision.withdraw => 'Retirar solicitud',
      BuyerRequestDecision.acceptChange => 'Aceptar cambios del proveedor',
      BuyerRequestDecision.rejectChange => 'Rechazar cambios del proveedor',
    };
    final explanation = switch (decision) {
      BuyerRequestDecision.withdraw =>
        'La solicitud dejará de estar disponible para su conversión en pedido.',
      BuyerRequestDecision.acceptChange => 'Se aplicarán las condiciones propuestas y la solicitud volverá a revisión.',
      BuyerRequestDecision.rejectChange => 'Se conservarán las condiciones anteriores y la solicitud volverá a revisión.',
    };
    final original = model.request;
    final originalProposal = model.proposal;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(explanation),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Volver'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirmar'),
          ),
        ],
      ),
    );
    if (confirmed == true &&
        context.mounted &&
        identical(original, model.request) &&
        identical(originalProposal, model.proposal)) {
      await model.decide(decision);
    }
  }
}

const _requestChangeEvents = {
  'sales.purchase-request.created',
  'sales.purchase-request.updated',
  'sales.purchase-request.submitted',
  'sales.purchase-request.material-change-proposed',
  'sales.purchase-request.material-change-accepted',
  'sales.purchase-request.material-change-rejected',
  'sales.purchase-request.approved',
  'sales.purchase-request.rejected',
  'sales.purchase-request.cancelled',
  'sales.purchase-request.withdrawn',
  'sales.purchase-request.converted',
  'resync-required',
};

final class _TermsCard extends StatelessWidget {
  const _TermsCard({required this.title, required this.terms});
  final String title;
  final BuyerRequestTerms terms;
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          Text('Entrega: ${nexaDateLabel(terms.deliveryDate)}'),
          if (terms.deliveryProfile != null) ...[
            const SizedBox(height: 8),
            Text('Condiciones de entrega: ${terms.deliveryProfile}'),
          ],
          Text('Pago: ${_payment(terms.paymentOption)}'),
          if (terms.priority != null) Text('Prioridad: ${terms.priority}'),
          if (terms.comment != null) ...[
            const SizedBox(height: 8),
            Text(terms.comment!),
          ],
          const SizedBox(height: 12),
          const Divider(),
          for (final line in terms.lines)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    line.name,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  Text(
                    '${line.quantity} ${line.unit}${line.presentation == null ? '' : ' · ${line.presentation}'}',
                  ),
                  if (line.price != null)
                    Text('${line.price} ${line.currency ?? ''} por unidad'),
                  if (line.notes != null) Text(line.notes!),
                ],
              ),
            ),
        ],
      ),
    ),
  );
}

String _payment(String? value) => switch (value) {
  'BANK_TRANSFER' => 'Transferencia bancaria',
  'CREDIT_LINE' => 'Línea de crédito',
  'CARD_STRIPE' => 'Tarjeta',
  'CASH' => 'Efectivo',
  'CASH_ON_DELIVERY' => 'Efectivo contra entrega',
  'WALLET' => 'Billetera',
  _ => value ?? 'Por confirmar',
};
