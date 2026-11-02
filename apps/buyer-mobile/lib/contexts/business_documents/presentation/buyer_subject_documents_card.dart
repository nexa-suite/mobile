import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../application/business_documents_repository.dart';
import 'business_documents_view_model.dart';

final class BuyerSubjectDocumentsCard extends StatefulWidget {
  const BuyerSubjectDocumentsCard({
    super.key,
    required this.subjectType,
    required this.subjectId,
    required this.visibleRoute,
  });
  final String subjectType, subjectId, visibleRoute;
  @override
  State<BuyerSubjectDocumentsCard> createState() =>
      _BuyerSubjectDocumentsCardState();
}

final class _BuyerSubjectDocumentsCardState
    extends State<BuyerSubjectDocumentsCard> {
  late BusinessDocumentsViewModel _model;
  BuyerVisibleRoutePoller? _poller;
  @override
  void initState() {
    super.initState();
    _createModel();
  }

  void _createModel() {
    _model = BusinessDocumentsViewModel(
      context.read<BusinessDocumentsRepository>(),
      context.read<BuyerAccessRepository>(),
      subjectType: widget.subjectType,
      subjectId: widget.subjectId,
    );
    final model = _model;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && identical(model, _model)) model.refresh();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _attachPoller();
  }

  void _attachPoller() {
    _poller ??= BuyerVisibleRoutePoller(
      router: GoRouter.of(context),
      routePath: widget.visibleRoute,
      onRefresh: _model.refresh,
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: BusinessDocumentsViewModel.readPermission)
          .map<void>((_) {}),
    );
  }

  @override
  void didUpdateWidget(covariant BuyerSubjectDocumentsCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.subjectId == widget.subjectId &&
        oldWidget.subjectType == widget.subjectType &&
        oldWidget.visibleRoute == widget.visibleRoute) {
      return;
    }
    _poller?.dispose();
    _poller = null;
    _model.dispose();
    _createModel();
    _attachPoller();
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
    builder: (context, _) {
      final access = context.read<BuyerAccessRepository>().snapshot;
      if (!access.hasActiveBuyerScope ||
          !access.currentContext!.permissions.contains(
            BusinessDocumentsViewModel.readPermission,
          )) {
        return const SizedBox.shrink();
      }
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Documentos del pedido',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              if (_model.status == BusinessDocumentsStatus.loading)
                const LinearProgressIndicator(),
              if (_model.status == BusinessDocumentsStatus.current &&
                  _model.items.isEmpty)
                const Text('Aún no hay documentos emitidos para este pedido.'),
              if (_model.status == BusinessDocumentsStatus.unavailable ||
                  _model.status ==
                      BusinessDocumentsStatus.permissionDenied) ...[
                Text(
                  _model.status == BusinessDocumentsStatus.permissionDenied
                      ? 'Tu acceso no permite consultar estos documentos.'
                      : 'No pudimos consultar los documentos de este pedido.',
                ),
                TextButton(
                  onPressed: _model.refresh,
                  child: const Text('Volver a consultar'),
                ),
              ],
              if (_model.status == BusinessDocumentsStatus.current)
                for (final document in _model.items)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.description_outlined),
                      title: Text(
                        document.documentNumber ?? _type(document.documentType),
                      ),
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '${_type(document.documentType)} · ${document.format}',
                          ),
                          NexaStatusBadge(document.status),
                        ],
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push(
                        '/documents/${Uri.encodeComponent(document.id)}',
                      ),
                    ),
                  ),
              if (_model.total > BusinessDocumentsViewModel.pageSize)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    IconButton(
                      tooltip: 'Documentos anteriores',
                      onPressed:
                          _model.status == BusinessDocumentsStatus.current &&
                              _model.page > 0
                          ? _model.previousPage
                          : null,
                      icon: const Icon(Icons.chevron_left),
                    ),
                    Text('Página ${_model.page + 1}'),
                    IconButton(
                      tooltip: 'Más documentos',
                      onPressed:
                          _model.status == BusinessDocumentsStatus.current &&
                              (_model.page + 1) *
                                      BusinessDocumentsViewModel.pageSize <
                                  _model.total
                          ? _model.nextPage
                          : null,
                      icon: const Icon(Icons.chevron_right),
                    ),
                  ],
                ),
            ],
          ),
        ),
      );
    },
  );
  String _type(String type) => switch (type) {
    'INVOICE' => 'Factura',
    'DISPATCH_GUIDE' => 'Guía de entrega',
    'PAYMENT_RECEIPT' => 'Comprobante de pago',
    _ => 'Documento',
  };
}
