import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/catalog_repository.dart';
import 'catalog_view_model.dart';

final class CatalogPage extends StatefulWidget {
  const CatalogPage({super.key});

  @override
  State<CatalogPage> createState() => _CatalogPageState();
}

final class _CatalogPageState extends State<CatalogPage> {
  final _search = TextEditingController();
  Timer? _searchTimer;
  late final CatalogViewModel _catalog;

  @override
  void initState() {
    super.initState();
    _catalog = context.read<CatalogViewModel>()..addListener(_syncInputs);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _catalog.refresh();
    });
  }

  void _syncInputs() {
    if (_catalog.status != CatalogLoadStatus.idle) return;
    if (_catalog.query.isEmpty && _search.text.isNotEmpty) _search.clear();
  }

  @override
  void dispose() {
    _searchTimer?.cancel();
    _catalog.removeListener(_syncInputs);
    _search.dispose();
    super.dispose();
  }

  void _searchChanged(String value) {
    _searchTimer?.cancel();
    _searchTimer = Timer(const Duration(milliseconds: 300), () {
      if (mounted) context.read<CatalogViewModel>().search(value);
    });
  }

  Future<void> _submitSearch(String value) async {
    _searchTimer?.cancel();
    await context.read<CatalogViewModel>().search(value);
  }

  void _clearFilters() {
    _searchTimer?.cancel();
    _catalog.filter(query: _search.text, brand: '', category: '');
  }

  @override
  Widget build(BuildContext context) {
    final catalog = context.watch<CatalogViewModel>();
    final theme = Theme.of(context);
    final hasFilters = catalog.brand.isNotEmpty || catalog.category.isNotEmpty;
    return NexaPageWidth(
      padding: EdgeInsets.zero,
      child: Column(
        children: [
          const NexaSectionHeader(
            eyebrow: 'Compra mayorista',
            title: 'Catálogo',
            description: 'Consulta productos, precio y disponibilidad de tu relación activa.',
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    TextField(
                      key: const Key('catalog-product-query'),
                      controller: _search,
                      maxLength: 120,
                      textInputAction: TextInputAction.search,
                      onChanged: _searchChanged,
                      onSubmitted: _submitSearch,
                      decoration: InputDecoration(
                        hintText: 'Producto, marca o SKU',
                        prefixIcon: const Icon(Icons.search),
                        counterText: '',
                        suffixIcon: _search.text.isEmpty
                            ? null
                            : IconButton(
                                tooltip: 'Borrar búsqueda',
                                icon: const Icon(Icons.close),
                                onPressed: () {
                                  _search.clear();
                                  _searchChanged('');
                                },
                              ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    LayoutBuilder(
                      builder: (context, constraints) {
                        final filterWidth = constraints.maxWidth >= 500
                            ? (constraints.maxWidth - 12) / 2
                            : constraints.maxWidth;
                        return Wrap(
                          spacing: 12,
                          runSpacing: 10,
                          children: [
                            SizedBox(
                              width: filterWidth,
                              child: OutlinedButton.icon(
                                key: const Key('catalog-brand-filter'),
                                onPressed: () => _openSelector(
                                  context,
                                  CatalogFilterKind.brand,
                                ),
                                icon: const Icon(Icons.local_offer_outlined),
                                label: Text(
                                  catalog.brand.isEmpty
                                      ? 'Todas las marcas'
                                      : 'Marca: ${catalog.brand}',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ),
                            SizedBox(
                              width: filterWidth,
                              child: OutlinedButton.icon(
                                key: const Key('catalog-type-filter'),
                                onPressed: () => _openSelector(
                                  context,
                                  CatalogFilterKind.category,
                                ),
                                icon: const Icon(Icons.category_outlined),
                                label: Text(
                                  catalog.category.isEmpty
                                      ? 'Todas las categorías'
                                      : 'Categoría: ${catalog.category}',
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ),
                          ],
                        );
                      },
                    ),
                    if (catalog.status == CatalogLoadStatus.current ||
                        catalog.items.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          if (hasFilters)
                            TextButton.icon(
                              onPressed: _clearFilters,
                              icon: const Icon(Icons.close, size: 16),
                              label: const Text('Limpiar filtros'),
                              style: TextButton.styleFrom(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 8,
                                ),
                              ),
                            )
                          else
                            Icon(
                              Icons.shopping_bag_outlined,
                              size: 17,
                              color: theme.colorScheme.primary,
                            ),
                          const Spacer(),
                          Text(
                            catalog.status == CatalogLoadStatus.loading
                                ? 'Actualizando resultados…'
                                : '${catalog.totalItems} productos',
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
          if (catalog.status == CatalogLoadStatus.loading &&
              catalog.items.isNotEmpty)
            const LinearProgressIndicator(),
          Expanded(child: _catalogContent(catalog)),
        ],
      ),
    );
  }

  Future<void> _openSelector(
    BuildContext context,
    CatalogFilterKind kind,
  ) async => showDialog<void>(
    context: context,
    builder: (_) => _CatalogFilterSelectorDialog(
      model: _catalog,
      kind: kind,
      selectedName: kind == CatalogFilterKind.brand
          ? _catalog.brand
          : _catalog.category,
    ),
  );

  Widget _catalogContent(CatalogViewModel catalog) {
    if (catalog.status == CatalogLoadStatus.loading && catalog.items.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 14),
            Text(
              'Consultando catálogo…',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ],
        ),
      );
    }
    if ((catalog.status == CatalogLoadStatus.unavailable ||
            catalog.status == CatalogLoadStatus.permissionDenied) &&
        catalog.items.isEmpty) {
      return _EmptyState(
        message: catalog.message ?? 'No se pudo consultar el catálogo.',
        icon: catalog.status == CatalogLoadStatus.permissionDenied
            ? Icons.lock_outline
            : Icons.cloud_off_outlined,
        actionLabel: catalog.status == CatalogLoadStatus.permissionDenied
            ? null
            : 'Reintentar',
        onAction: catalog.status == CatalogLoadStatus.permissionDenied
            ? null
            : catalog.refresh,
      );
    }
    if (catalog.items.isEmpty && catalog.status == CatalogLoadStatus.current) {
      return const _EmptyState(
        icon: Icons.search_off_outlined,
        message: 'No se encontraron productos.',
      );
    }

    final hasStaleItems =
        catalog.status == CatalogLoadStatus.unavailable &&
        catalog.items.isNotEmpty;

    return LayoutBuilder(
      builder: (context, constraints) {
        final useGrid = constraints.maxWidth >= 600;
        return RefreshIndicator(
          onRefresh: catalog.refresh,
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              if (hasStaleItems)
                const SliverToBoxAdapter(
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(16, 8, 16, 4),
                    child: NexaNotice(
                      'No se pudo actualizar el catálogo. La información anterior puede estar desactualizada; no confirma precio, disponibilidad ni pedido.',
                      isError: true,
                    ),
                  ),
                ),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                sliver: useGrid
                    ? SliverGrid(
                        gridDelegate:
                            const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 2,
                              crossAxisSpacing: 12,
                              mainAxisSpacing: 12,
                              mainAxisExtent: 336,
                            ),
                        delegate: SliverChildBuilderDelegate(
                          (context, index) =>
                              _catalogCard(context, catalog.items[index]),
                          childCount: catalog.items.length,
                        ),
                      )
                    : SliverList(
                        delegate: SliverChildBuilderDelegate(
                          (context, index) => Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: _catalogCard(context, catalog.items[index]),
                          ),
                          childCount: catalog.items.length,
                        ),
                      ),
              ),
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  child: _PageControls(
                    page: catalog.page,
                    totalPages: catalog.totalPages,
                    totalItems: catalog.totalItems,
                    onPrevious: catalog.previousPage,
                    onNext: catalog.nextPage,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _catalogCard(BuildContext context, CatalogItemProjection item) =>
      _CatalogItemCard(
        item: item,
        onTap: () =>
            context.push('/catalog/${Uri.encodeComponent(item.catalogItemId)}'),
      );
}

final class _CatalogFilterSelectorDialog extends StatefulWidget {
  const _CatalogFilterSelectorDialog({
    required this.model,
    required this.kind,
    required this.selectedName,
  });

  final CatalogViewModel model;
  final CatalogFilterKind kind;
  final String selectedName;

  @override
  State<_CatalogFilterSelectorDialog> createState() =>
      _CatalogFilterSelectorDialogState();
}

final class _CatalogFilterSelectorDialogState
    extends State<_CatalogFilterSelectorDialog> {
  final _search = TextEditingController();
  Timer? _searchTimer;

  String get _label =>
      widget.kind == CatalogFilterKind.brand ? 'Marca' : 'Categoría';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _load(page: 0, search: '');
    });
  }

  void _load({required int page, required String search}) {
    widget.model.loadFilterOptions(
      kind: widget.kind,
      search: search,
      page: page,
    );
  }

  void _searchChanged(String value) {
    _searchTimer?.cancel();
    _searchTimer = Timer(const Duration(milliseconds: 300), () {
      if (mounted) _load(page: 0, search: value.trim());
    });
  }

  @override
  void dispose() {
    _searchTimer?.cancel();
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text('Seleccionar $_label'),
    content: SizedBox(
      width: 440,
      height: 440,
      child: AnimatedBuilder(
        animation: widget.model,
        builder: (context, _) {
          final state = widget.model.filterOptionsState(widget.kind);
          return Column(
            children: [
              TextField(
                key: Key('catalog-${widget.kind.name}-lookup-search'),
                controller: _search,
                maxLength: 120,
                onChanged: _searchChanged,
                decoration: InputDecoration(
                  labelText: 'Buscar $_label',
                  prefixIcon: const Icon(Icons.search),
                  counterText: '',
                ),
              ),
              if (state.status == CatalogFilterOptionsStatus.loading)
                const LinearProgressIndicator(),
              if (state.message case final message?) ...[
                const SizedBox(height: 8),
                Text(message, textAlign: TextAlign.center),
              ],
              Expanded(
                child:
                    state.status == CatalogFilterOptionsStatus.current &&
                        state.items.isEmpty
                    ? Center(child: Text('No hay opciones de $_label.'))
                    : ListView.builder(
                        itemCount: state.items.length,
                        itemBuilder: (context, index) {
                          final option = state.items[index];
                          return ListTile(
                            key: Key(
                              'catalog-${widget.kind.name}-option-${option.id}',
                            ),
                            title: Text(option.name),
                            trailing: option.name == widget.selectedName
                                ? const Icon(Icons.check)
                                : null,
                            onTap: () {
                              widget.model.selectFilterOption(
                                kind: widget.kind,
                                option: option,
                              );
                              Navigator.of(context).pop();
                            },
                          );
                        },
                      ),
              ),
              if (state.status == CatalogFilterOptionsStatus.unavailable ||
                  state.status == CatalogFilterOptionsStatus.permissionDenied)
                TextButton(
                  onPressed: () =>
                      _load(page: state.page, search: state.search),
                  child: const Text('Reintentar'),
                ),
              if (state.totalPages > 1)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    IconButton(
                      tooltip: 'Opciones anteriores',
                      onPressed:
                          state.page > 0 &&
                              state.status != CatalogFilterOptionsStatus.loading
                          ? () => _load(
                              page: state.page - 1,
                              search: state.search,
                            )
                          : null,
                      icon: const Icon(Icons.chevron_left),
                    ),
                    Text('Página ${state.page + 1} de ${state.totalPages}'),
                    IconButton(
                      tooltip: 'Más opciones',
                      onPressed:
                          state.page + 1 < state.totalPages &&
                              state.status != CatalogFilterOptionsStatus.loading
                          ? () => _load(
                              page: state.page + 1,
                              search: state.search,
                            )
                          : null,
                      icon: const Icon(Icons.chevron_right),
                    ),
                  ],
                ),
            ],
          );
        },
      ),
    ),
    actions: [
      TextButton(
        onPressed: () {
          widget.model.selectFilterOption(kind: widget.kind);
          Navigator.of(context).pop();
        },
        child: const Text('Quitar filtro'),
      ),
      TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Cerrar'),
      ),
    ],
  );
}

final class _CatalogItemCard extends StatelessWidget {
  const _CatalogItemCard({required this.item, required this.onTap});

  final CatalogItemProjection item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final details = [
      if (item.productFamilyName != null &&
          item.productFamilyName != item.itemName)
        item.productFamilyName,
      if (item.productVariantName != null) item.productVariantName,
      item.presentation,
      if (item.skuCode != null) 'SKU ${item.skuCode}',
    ].whereType<String>().where((value) => value.isNotEmpty).join(' · ');
    final theme = Theme.of(context);

    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              height: 96,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  NexaProductVisual(name: item.itemName, height: 96),
                  if (item.categoryName != null)
                    Positioned(
                      left: 12,
                      right: 12,
                      bottom: 12,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: theme.colorScheme.surface.withValues(
                            alpha: .94,
                          ),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 10,
                            vertical: 6,
                          ),
                          child: Text(
                            item.categoryName!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (item.brandName != null && item.brandName!.isNotEmpty) ...[
                    Text(
                      item.brandName!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.primary,
                      ),
                    ),
                    const SizedBox(height: 5),
                  ],
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.itemName,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleMedium,
                        ),
                      ),
                      Icon(
                        Icons.chevron_right,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ],
                  ),
                  if (details.isNotEmpty) ...[
                    const SizedBox(height: 7),
                    Text(
                      details,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                  const SizedBox(height: 10),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      NexaStatusBadge(
                        item.availabilityStatus,
                        label: _availabilityLabel(item.availabilityStatus),
                      ),
                      if (item.price case final price?)
                        Text(
                          '${price.amount} ${price.currency}',
                          style: theme.textTheme.titleSmall?.copyWith(
                            color: theme.colorScheme.primary,
                          ),
                        ),
                      if (item.unitOfMeasure case final unit?)
                        Text(
                          'Unidad: $unit',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _availabilityLabel(String status) => switch (status.toUpperCase()) {
    'AVAILABLE' => 'Disponible',
    'LOW' => 'Disponibilidad baja',
    'UNAVAILABLE' => 'No disponible',
    _ => 'Disponibilidad por confirmar',
  };
}

final class _PageControls extends StatelessWidget {
  const _PageControls({
    required this.page,
    required this.totalPages,
    required this.totalItems,
    required this.onPrevious,
    required this.onNext,
  });

  final int page;
  final int totalPages;
  final int totalItems;
  final Future<void> Function() onPrevious;
  final Future<void> Function() onNext;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        IconButton(
          tooltip: 'Página anterior',
          onPressed: page > 0 ? onPrevious : null,
          icon: const Icon(Icons.chevron_left),
        ),
        Flexible(
          child: Text(
            '${page + 1} de ${totalPages == 0 ? 1 : totalPages} · $totalItems productos',
          ),
        ),
        IconButton(
          tooltip: 'Página siguiente',
          onPressed: page + 1 < totalPages ? onNext : null,
          icon: const Icon(Icons.chevron_right),
        ),
      ],
    ),
  );
}

final class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.message,
    this.icon = Icons.inventory_2_outlined,
    this.actionLabel,
    this.onAction,
  });

  final String message;
  final IconData icon;
  final String? actionLabel;
  final Future<void> Function()? onAction;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            size: 32,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          if (actionLabel != null && onAction != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onAction, child: Text(actionLabel!)),
          ],
        ],
      ),
    ),
  );
}
