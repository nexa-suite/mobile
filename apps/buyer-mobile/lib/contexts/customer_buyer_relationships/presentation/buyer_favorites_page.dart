import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import 'buyer_favorites_view_model.dart';

final class BuyerFavoritesPage extends StatefulWidget {
  const BuyerFavoritesPage({super.key});
  @override
  State<BuyerFavoritesPage> createState() => _BuyerFavoritesPageState();
}

final class _BuyerFavoritesPageState extends State<BuyerFavoritesPage> {
  BuyerVisibleRoutePoller? _poller;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerFavoritesViewModel>().load();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _poller ??= BuyerVisibleRoutePoller(
      router: GoRouter.of(context),
      routePath: '/favorites',
      onRefresh: () {
        final model = context.read<BuyerFavoritesViewModel>();
        return model.load(page: model.page);
      },
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final model = context.watch<BuyerFavoritesViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('Favoritos')),
      body: SafeArea(
        top: false,
        child: NexaPageWidth(
          padding: EdgeInsets.zero,
          child: RefreshIndicator(
            onRefresh: () => model.load(page: model.page),
            child: ListView(
              padding: const EdgeInsets.all(16),
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                const Text(
                  'Guarda los productos que compras seguido para encontrarlos fácilmente.',
                ),
                const SizedBox(height: 16),
                if (!model.permitted)
                  const NexaNotice(
                    'No tienes acceso a favoritos en esta cuenta.',
                  ),
                if (model.status == BuyerFavoritesStatus.loading || model.busy)
                  const LinearProgressIndicator(),
                if (model.message case final message?) ...[
                  NexaNotice(message, isError: model.messageIsError),
                  const SizedBox(height: 12),
                ],
                if (model.canRetry)
                  OutlinedButton(
                    onPressed: model.retry,
                    child: const Text('Reintentar el cambio pendiente'),
                  ),
                if (model.status == BuyerFavoritesStatus.unavailable &&
                    !model.canRetry)
                  OutlinedButton(
                    onPressed: () => model.load(page: model.page),
                    child: const Text('Volver a consultar'),
                  ),
                if (model.status == BuyerFavoritesStatus.current &&
                    model.items.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 32),
                    child: Text(
                      'Aún no hay productos en esta página de favoritos. Puedes guardarlos desde el detalle del catálogo.',
                      textAlign: TextAlign.center,
                    ),
                  ),
                for (final item in model.items)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(
                            item.name ?? 'Producto ya no disponible',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          if (item.brand != null) Text(item.brand!),
                          if (item.amount != null && item.currency != null)
                            Text(
                              '${item.amount} ${item.currency}${item.unit == null ? '' : ' · ${item.unit}'}',
                            ),
                          const SizedBox(height: 12),
                          if (item.catalogItemId != null)
                            FilledButton.tonalIcon(
                              onPressed: () => context.push(
                                '/catalog/${Uri.encodeComponent(item.catalogItemId!)}',
                              ),
                              icon: const Icon(Icons.shopping_bag_outlined),
                              label: const Text('Ver producto'),
                            ),
                          if (model.canManage)
                            TextButton.icon(
                              onPressed: model.canEdit
                                  ? () => model.setSaved(
                                      item.sellableSkuId,
                                      false,
                                    )
                                  : null,
                              icon: const Icon(Icons.favorite_border),
                              label: const Text('Quitar de favoritos'),
                            ),
                        ],
                      ),
                    ),
                  ),
                if (model.totalPages > 1)
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      IconButton(
                        tooltip: 'Página anterior',
                        onPressed: model.canBrowse && model.page > 0
                            ? () => model.load(page: model.page - 1)
                            : null,
                        icon: const Icon(Icons.chevron_left),
                      ),
                      Text('${model.page + 1} de ${model.totalPages}'),
                      IconButton(
                        tooltip: 'Página siguiente',
                        onPressed:
                            model.canBrowse && model.page + 1 < model.totalPages
                            ? () => model.load(page: model.page + 1)
                            : null,
                        icon: const Icon(Icons.chevron_right),
                      ),
                    ],
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

final class BuyerSaveFavoriteButton extends StatefulWidget {
  const BuyerSaveFavoriteButton({
    super.key,
    required this.sellableSkuId,
    required this.routePath,
  });
  final String? sellableSkuId;
  final String routePath;
  @override
  State<BuyerSaveFavoriteButton> createState() =>
      _BuyerSaveFavoriteButtonState();
}

final class _BuyerSaveFavoriteButtonState
    extends State<BuyerSaveFavoriteButton> {
  BuyerVisibleRoutePoller? _poller;
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant BuyerSaveFavoriteButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.sellableSkuId != widget.sellableSkuId) _load();
    if (oldWidget.routePath != widget.routePath) {
      _poller?.dispose();
      _poller = null;
      _attachPoller();
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _attachPoller();
  }

  void _attachPoller() {
    _poller ??= BuyerVisibleRoutePoller(
      router: GoRouter.of(context),
      routePath: widget.routePath,
      onRefresh: () async {
        final sku = widget.sellableSkuId;
        if (mounted && sku != null) {
          await context.read<BuyerFavoritesViewModel>().loadStatus(sku);
        }
      },
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    super.dispose();
  }

  void _load() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final sku = widget.sellableSkuId;
      if (mounted && sku != null) {
        context.read<BuyerFavoritesViewModel>().loadStatus(sku);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final model = context.watch<BuyerFavoritesViewModel>();
    final sku = widget.sellableSkuId;
    if (!model.canManage || sku == null) return const SizedBox.shrink();
    final saved = model.savedStatus(sku), checking = model.checkingStatus(sku);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton.icon(
          onPressed: !model.canEdit || checking
              ? null
              : saved == null
              ? () => model.loadStatus(sku)
              : () => model.setSaved(sku, !saved),
          icon: checking
              ? const SizedBox.square(
                  dimension: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : Icon(
                  saved == true
                      ? Icons.favorite
                      : saved == null
                      ? Icons.refresh
                      : Icons.favorite_border,
                ),
          label: Text(
            checking
                ? 'Consultando favoritos…'
                : saved == null
                ? 'Consultar si está guardado'
                : saved
                ? 'Quitar de favoritos'
                : 'Guardar en favoritos',
          ),
        ),
        if (model.message case final message?)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Semantics(
              liveRegion: true,
              child: NexaNotice(message, isError: model.messageIsError),
            ),
          ),
        if (model.canRetry)
          TextButton(
            onPressed: model.retry,
            child: const Text('Reintentar el cambio pendiente'),
          ),
      ],
    );
  }
}
