import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/presentation/nexa_buyer_palette.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../application/catalog_repository.dart';
import 'catalog_commercial_format.dart';
import 'buyer_product_image.dart';
import 'catalog_view_model.dart';

final class CatalogPage extends StatefulWidget {
  const CatalogPage({super.key, this.shoppingSummary});
  final Widget? shoppingSummary;

  @override
  State<CatalogPage> createState() => _CatalogPageState();
}

final class _CatalogPageState extends State<CatalogPage> {
  final _search = TextEditingController();
  Timer? _searchTimer;
  late final CatalogViewModel _catalog;
  GoRouter? _router;
  BuyerVisibleRoutePoller? _poller;

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
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.maybeOf(context);
    if (router == null) return;
    if (identical(router, _router)) return;
    _poller?.dispose();
    _router = router;
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: '/catalog',
      onRefresh: () => context.read<CatalogViewModel>().refresh(),
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: CatalogViewModel.readPermission)
          .where((event) => event.type == 'resync-required')
          .map<void>((_) {}),
    );
  }

  @override
  void dispose() {
    _searchTimer?.cancel();
    _poller?.dispose();
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
    _catalog.filter(
      query: _search.text,
      brand: '',
      brandId: '',
      category: '',
      categoryId: '',
      family: '',
      familyId: '',
      variant: '',
      variantId: '',
      coldChain: '',
      availableOnly: false,
    );
  }

  @override
  Widget build(BuildContext context) {
    final catalog = context.watch<CatalogViewModel>();
    final theme = Theme.of(context);
    final hasFilters =
        catalog.brand.isNotEmpty ||
        catalog.brandId.isNotEmpty ||
        catalog.category.isNotEmpty ||
        catalog.categoryId.isNotEmpty ||
        catalog.family.isNotEmpty ||
        catalog.familyId.isNotEmpty ||
        catalog.variant.isNotEmpty ||
        catalog.variantId.isNotEmpty ||
        catalog.coldChain.isNotEmpty ||
        catalog.availableOnly;
    return NexaPageWidth(
      maxWidth: 1120,
      padding: EdgeInsets.zero,
      child: NestedScrollView(
        headerSliverBuilder: (context, innerBoxIsScrolled) => [
          SliverToBoxAdapter(
            child: Card.outlined(
              margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              clipBehavior: Clip.antiAlias,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
                    child: Text(
                      'Catálogo',
                      style: theme.textTheme.headlineSmall,
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        ConstrainedBox(
                          constraints: const BoxConstraints(minHeight: 52),
                          child: TextField(
                            key: const Key('catalog-product-query'),
                            controller: _search,
                            maxLength: 120,
                            textInputAction: TextInputAction.search,
                            onChanged: _searchChanged,
                            onSubmitted: _submitSearch,
                            decoration: InputDecoration(
                              isDense: true,
                              contentPadding: const EdgeInsets.symmetric(
                                vertical: 12,
                                horizontal: 12,
                              ),
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
                        ),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 4,
                          children: [
                            ActionChip(
                              labelStyle: theme.textTheme.labelMedium,
                              key: const Key('catalog-brand-filter'),
                              avatar: const Icon(Icons.local_offer_outlined, size: 18),
                              label: Text(
                                catalog.brand.isEmpty
                                    ? 'Todas las marcas'
                                    : catalog.brand,
                              ),
                              onPressed: () => _openSelector(context, CatalogFilterKind.brand),
                            ),
                            Semantics(
                              key: const Key('catalog-category-filter'),
                              button: true,
                              child: ActionChip(
                                labelStyle: theme.textTheme.labelMedium,
                                key: const Key('catalog-type-filter'),
                                avatar: const Icon(
                                  Icons.category_outlined,
                                  size: 18,
                                ),
                                label: Text(
                                  catalog.category.isEmpty
                                      ? 'Todas las categorías'
                                      : buyerCatalogCategoryLabel(
                                          catalog.category,
                                        ),
                                ),
                                onPressed: () => _openSelector(
                                  context,
                                  CatalogFilterKind.category,
                                ),
                              ),
                            ),
                            ActionChip(
                              labelStyle: theme.textTheme.labelMedium,
                              avatar: const Icon(Icons.tune_rounded, size: 18),
                              label: Text(
                                catalog.availableOnly ||
                                        catalog.coldChain.isNotEmpty
                                    ? 'Filtros activos'
                                    : 'Más filtros',
                              ),
                              onPressed: () =>
                                  _openAvailabilityFilters(context),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  if (catalog.status == CatalogLoadStatus.current ||
                      catalog.items.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                      child: Row(
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
                            ),
                          const Spacer(),
                          Text(
                            catalog.status == CatalogLoadStatus.loading
                                ? 'Actualizando resultados…'
                                : buyerCatalogProductCountLabel(
                                    catalog.totalItems,
                                  ),
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.onSurfaceVariant,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ),
                  if (catalog.status == CatalogLoadStatus.loading &&
                      catalog.items.isNotEmpty)
                    const LinearProgressIndicator(),
                ],
              ),
            ),
          ),
        ],
        body: _catalogContent(catalog),
      ),
    );
  }

  Future<void> _openAvailabilityFilters(
    BuildContext context,
  ) => showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    useSafeArea: true,
    isScrollControlled: true,
    builder: (context) => AnimatedBuilder(
      animation: _catalog,
      builder: (context, _) {
        final catalog = _catalog;
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Disponibilidad y conservación',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 16),
              SwitchListTile.adaptive(
                contentPadding: EdgeInsets.zero,
                title: const Text('Solo disponibles'),
                value: catalog.availableOnly,
                onChanged: (value) => catalog.filter(
                  brand: catalog.brand,
                  brandId: catalog.brandId,
                  category: catalog.category,
                  categoryId: catalog.categoryId,
                  family: catalog.family,
                  familyId: catalog.familyId,
                  variant: catalog.variant,
                  variantId: catalog.variantId,
                  availableOnly: value,
                ),
              ),
              DropdownButtonFormField<String>(
                key: ValueKey(catalog.coldChain),
                initialValue: catalog.coldChain,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Conservación'),
                items: const [
                  DropdownMenuItem(value: '', child: Text('Todas')),
                  DropdownMenuItem(
                    value: 'REFRIGERATED',
                    child: Text('Refrigerados'),
                  ),
                  DropdownMenuItem(value: 'FROZEN', child: Text('Congelados')),
                  DropdownMenuItem(
                    value: 'NONE',
                    child: Text('Temperatura ambiente'),
                  ),
                ],
                onChanged: (value) => catalog.filter(
                  brand: catalog.brand,
                  brandId: catalog.brandId,
                  category: catalog.category,
                  categoryId: catalog.categoryId,
                  family: catalog.family,
                  familyId: catalog.familyId,
                  variant: catalog.variant,
                  variantId: catalog.variantId,
                  coldChain: value ?? '',
                ),
              ),

              const SizedBox(height: 16),
              FilledButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Ver productos'),
              ),
            ],
          ),
        );
      },
    ),
  );

  Future<void> _openSelector(
    BuildContext context,
    CatalogFilterKind kind,
  ) async => showDialog<void>(
    context: context,
    builder: (_) => _CatalogFilterSelectorDialog(
      model: _catalog,
      kind: kind,
      selectedName: switch (kind) {
        CatalogFilterKind.brand => _catalog.brand,
        CatalogFilterKind.category => _catalog.category,
        CatalogFilterKind.family => _catalog.family,
        CatalogFilterKind.variant => _catalog.variant,
      },
      selectedId: switch (kind) {
        CatalogFilterKind.brand => _catalog.brandId,
        CatalogFilterKind.category => _catalog.categoryId,
        CatalogFilterKind.family => _catalog.familyId,
        CatalogFilterKind.variant => _catalog.variantId,
      },
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
        final textScale = MediaQuery.textScalerOf(context).scale(16) / 16;
        final useGrid = constraints.maxWidth >= 600 && textScale < 1.3;
        return RefreshIndicator(
          onRefresh: catalog.refresh,
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              if (widget.shoppingSummary != null)
                SliverToBoxAdapter(child: widget.shoppingSummary!),
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
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: constraints.maxWidth >= 1000 ? 3 : 2,
                          crossAxisSpacing: 12,
                          mainAxisSpacing: 12,
                          mainAxisExtent: 244 + (textScale - 1) * 200,
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

  Widget _catalogCard(
    BuildContext context,
    CatalogItemProjection item,
  ) => _CatalogItemCard(
    item: item,
    onTap: () =>
        context.push('/catalog/${Uri.encodeComponent(item.catalogItemId)}'),
    onCreateRequest:
        _catalog.canCreateRequest &&
            RegExp(
              r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
            ).hasMatch(item.sellableSkuId ?? '')
        ? () => context.push(
            '/catalog/${Uri.encodeComponent(item.catalogItemId)}/purchase-request',
          )
        : null,
  );
}

final class _CatalogFilterSelectorDialog extends StatefulWidget {
  const _CatalogFilterSelectorDialog({
    required this.model,
    required this.kind,
    required this.selectedName,
    required this.selectedId,
  });

  final CatalogViewModel model;
  final CatalogFilterKind kind;
  final String selectedName;
  final String? selectedId;

  @override
  State<_CatalogFilterSelectorDialog> createState() =>
      _CatalogFilterSelectorDialogState();
}

final class _CatalogFilterSelectorDialogState
    extends State<_CatalogFilterSelectorDialog> {
  final _search = TextEditingController();
  Timer? _searchTimer;

  String get _label => switch (widget.kind) {
    CatalogFilterKind.brand => 'Marca',
    CatalogFilterKind.category => 'Tipo',
    CatalogFilterKind.family => 'Familia',
    CatalogFilterKind.variant => 'Variedad',
  };

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
      familyId: widget.kind == CatalogFilterKind.variant
          ? widget.model.familyId
          : null,
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
                            title: Text(
                              widget.kind == CatalogFilterKind.category
                                  ? buyerCatalogCategoryLabel(option.name)
                                  : option.name,
                            ),
                            trailing:
                                (widget.selectedId?.isNotEmpty == true
                                    ? option.id == widget.selectedId
                                    : option.name == widget.selectedName)
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
  const _CatalogItemCard({
    required this.item,
    required this.onTap,
    this.onCreateRequest,
  });

  final CatalogItemProjection item;
  final VoidCallback onTap;
  final VoidCallback? onCreateRequest;

  @override
  Widget build(BuildContext context) {
    final details = [
      if (item.brandName != null) 'Marca: ${item.brandName}',
      if (item.categoryName != null)
        'Tipo: ${buyerCatalogCategoryLabel(item.categoryName!)}',
      if (item.coldChainRequirement != null)
        'Conservación: ${_coldChainLabel(item.coldChainRequirement!)}',
      if (item.productFamilyName != null && item.productFamilyName != item.itemName)
        'Familia: ${item.productFamilyName}',
      if (item.productVariantName != null)
        'Variedad: ${item.productVariantName}',
      if (item.sellableAvailability case final available?)
        'Disponible: ${_quantityLabel(available)}'
            '${item.unitOfMeasure == null ? '' : ' ${item.unitOfMeasure}'}',
      if (item.presentation?.trim().isNotEmpty == true) item.presentation!.trim(),
      if (item.skuCode != null) 'SKU ${item.skuCode}',
    ].whereType<String>().where((value) => value.isNotEmpty).join(' · ');
    final theme = Theme.of(context);
    final quantityIsAvailable = const {
      'AVAILABLE',
      'LOW',
    }.contains(item.availabilityStatus.toUpperCase());
    final priceLabel = buyerCatalogPriceLabel(
      item.price?.amount,
      item.price?.currency,
    );
    final stockQuantity = item.sellableAvailability;

    final storage = item.coldChainRequirement?.toUpperCase();
    final imageWidth = MediaQuery.textScalerOf(context).scale(16) / 16 > 1.2
        ? 128.0
        : 104.0;
    final frozen = storage == 'FROZEN';
    final chilled = storage == 'REFRIGERATED';
    final photoSurface = frozen
        ? NexaBuyerPalette.frozenSurface
        : chilled
        ? NexaBuyerPalette.coldSurface
        : theme.colorScheme.surfaceContainer;
    final photoInk = frozen ? NexaBuyerPalette.frozen : NexaBuyerPalette.cold;
    return Card.outlined(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: imageWidth,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.circular(10),
                          child: BuyerProductImage(
                            name: item.itemName,
                            url: item.imageUrl,
                            fileName: item.imageFileName,
                            height: 112,
                          ),
                        ),
                        if (frozen || chilled) ...[
                          const SizedBox(height: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 4,
                            ),
                            decoration: BoxDecoration(
                              color: photoSurface,
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  frozen
                                      ? Icons.ac_unit
                                      : Icons.thermostat_outlined,
                                  size: 12,
                                  color: photoInk,
                                ),
                                const SizedBox(width: 3),
                                Flexible(
                                  child: Text(
                                    frozen ? 'Congelado' : 'Refrigerado',
                                    style: theme.textTheme.labelSmall?.copyWith(
                                      color: photoInk,
                                      fontSize: 10,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                item.brandName ?? item.categoryName ?? '',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.labelSmall?.copyWith(
                                  color: NexaBuyerPalette.muted,
                                ),
                              ),
                            ),
                            const Icon(
                              Icons.chevron_right,
                              size: 18,
                              color: NexaBuyerPalette.muted,
                            ),
                          ],
                        ),
                        const SizedBox(height: 5),
                        Text(
                          item.itemName,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontSize: 14,
                          ),
                        ),
                        if (details.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            details,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall,
                          ),
                        ],
                        const SizedBox(height: 10),
                        Text(
                          priceLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.titleLarge,
                        ),
                        if (item.promotionLabel case final promotion?
                            when promotion.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            promotion,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: NexaBuyerPalette.attention,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        NexaStatusBadge(
                          item.availabilityStatus,
                          label: _availabilityLabel(item.availabilityStatus),
                        ),
                        if (quantityIsAvailable && stockQuantity != null)
                          Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text(
                              buyerCatalogQuantityLabel(
                                stockQuantity,
                                item.unitOfMeasure,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodySmall,
                            ),
                          ),
                      ],
                    ),
                  ),
                  if (onCreateRequest != null) ...[
                    const SizedBox(width: 8),
                    OutlinedButton.icon(
                      onPressed: onCreateRequest,
                      icon: const Icon(
                        Icons.add_shopping_cart_outlined,
                        size: 18,
                      ),
                      label: const Text('Solicitar'),
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 10),
                      ),
                    ),
                  ],
                ],
              ),
            ],
          ),
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

  String _coldChainLabel(String requirement) =>
      switch (requirement.trim().toUpperCase()) {
        'NONE' => 'No requiere frío',
        'REFRIGERATED' => 'Refrigerado',
        'FROZEN' => 'Congelado',
        _ => 'Por confirmar',
      };

  String _quantityLabel(num quantity) {
    final value = quantity.toString();
    if (value.endsWith('.0')) return value.substring(0, value.length - 2);
    return value.replaceAll('.', ',');
  }
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
            '${page + 1} de ${totalPages == 0 ? 1 : totalPages} · ${buyerCatalogProductCountLabel(totalItems)}',
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
