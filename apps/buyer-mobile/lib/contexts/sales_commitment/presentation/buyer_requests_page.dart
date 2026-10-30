import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/presentation/nexa_display_format.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import 'buyer_requests_view_model.dart';

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

final class BuyerRequestsPage extends StatefulWidget {
  const BuyerRequestsPage({super.key});
  @override
  State<BuyerRequestsPage> createState() => _BuyerRequestsPageState();
}

final class _BuyerRequestsPageState extends State<BuyerRequestsPage> {
  GoRouter? _router;
  BuyerVisibleRoutePoller? _poller;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerRequestsViewModel>().refresh();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.maybeOf(context);
    if (router == null) return;
    if (identical(router, _router)) return;
    _poller?.dispose();
    _router = router;
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: '/requests',
      onRefresh: () => context.read<BuyerRequestsViewModel>().refresh(),
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
    final model = context.watch<BuyerRequestsViewModel>();
    final empty = model.showingDrafts
        ? model.drafts.isEmpty
        : model.requests.isEmpty;
    return NexaPageWidth(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          NexaSectionHeader(
            title: 'Mis solicitudes',
            eyebrow: 'Compras',
            description:
                'Continúa un borrador o consulta la revisión de tu proveedor.',
            action: IconButton(
              tooltip: 'Actualizar solicitudes',
              onPressed: model.busy ? null : model.refresh,
              icon: const Icon(Icons.refresh),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: SizedBox(
              width: double.infinity,
              child: SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(
                    value: false,
                    label: Text('Enviadas'),
                    icon: Icon(Icons.send_outlined),
                  ),
                  ButtonSegment(
                    value: true,
                    label: Text('Borradores'),
                    icon: Icon(Icons.edit_note),
                  ),
                ],
                selected: {model.showingDrafts},
                onSelectionChanged: model.busy
                    ? null
                    : (selection) => model.showDrafts(selection.first),
              ),
            ),
          ),
          if (!model.showingDrafts)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    for (final filter in <String?>[
                      null,
                      'SUBMITTED',
                      'CHANGES_PROPOSED',
                      'CONVERTED',
                      'REJECTED',
                    ])
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          label: Text(
                            filter == null ? 'Todas' : nexaStatusLabel(filter),
                          ),
                          selected: model.statusFilter == filter,
                          onSelected: model.busy
                              ? null
                              : (_) => model.filter(filter),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          const SizedBox(height: 12),
          if (model.busy) const LinearProgressIndicator(),
          Expanded(
            child: empty
                ? model.busy
                      ? const Center(child: CircularProgressIndicator())
                      : NexaEmptyState(
                          title:
                              model.message ??
                              (model.showingDrafts
                                  ? 'Tus borradores aparecerán aquí'
                                  : 'Todavía no hay solicitudes'),
                          description: 'Selecciona un producto del catálogo para preparar una solicitud de compra.',
                          icon: Icons.shopping_bag_outlined,
                          actionLabel: 'Actualizar',
                          onAction: model.refresh,
                        )
                : RefreshIndicator(
                    onRefresh: model.refresh,
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
                      children: [
                        if (model.message != null)
                          NexaNotice(model.message!, isError: true),
                        if (model.showingDrafts)
                          ...model.drafts.map(
                            (draft) => NexaRecordCard(
                              title: 'Solicitud en borrador',
                              status: draft.status,
                              icon: Icons.edit_note,
                              subtitle:
                                  '${draft.lineCount} ${draft.lineCount == 1 ? 'producto' : 'productos'} · Entrega ${nexaDateLabel(draft.requestedDeliveryDate, pending: 'por elegir')}',
                              footer: Text(
                                'Actualizado ${_date(draft.updatedAt)}',
                              ),
                              onTap: () => context.push(
                                '/requests/drafts/${Uri.encodeComponent(draft.id)}',
                              ),
                            ),
                          )
                        else
                          ...model.requests.map(
                            (request) => NexaRecordCard(
                              title: request.code,
                              reference: true,
                              status: request.status,
                              icon: Icons.shopping_bag_outlined,
                              subtitle:
                                  '${request.lineCount} ${request.lineCount == 1 ? 'producto' : 'productos'} · Entrega ${nexaDateLabel(request.terms.deliveryDate, pending: 'por confirmar')}',
                              onTap: () => context.push(
                                '/requests/${Uri.encodeComponent(request.id)}',
                              ),
                            ),
                          ),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            IconButton(
                              tooltip: 'Página anterior',
                              icon: const Icon(Icons.chevron_left),
                              onPressed: model.busy || model.page == 0
                                  ? null
                                  : () => model.load(model.page - 1),
                            ),
                            Text(
                              '${model.page + 1} de ${model.totalPages < 1 ? 1 : model.totalPages}',
                            ),
                            IconButton(
                              tooltip: 'Página siguiente',
                              icon: const Icon(Icons.chevron_right),
                              onPressed:
                                  model.busy ||
                                      model.page + 1 >= model.totalPages
                                  ? null
                                  : () => model.load(model.page + 1),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

String _date(DateTime? value) {
  if (value == null) return 'sin fecha';
  final local = value.toLocal();
  return '${local.day.toString().padLeft(2, '0')}/${local.month.toString().padLeft(2, '0')}/${local.year}';
}
