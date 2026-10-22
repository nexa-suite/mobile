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

  @override
  Widget build(BuildContext context) {
    final catalog = context.watch<CatalogViewModel>();
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
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              key: const Key('catalog-product-query'),
              controller: _search,
              maxLength: 120,
              textInputAction: TextInputAction.search,
              onChanged: _searchChanged,
              onSubmitted: _submitSearch,
              decoration: InputDecoration(
                hintText: 'Buscar productos',
                prefixIcon: const Icon(Icons.search),
                filled: true,
                fillColor: Theme.of(context).colorScheme.surface,
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
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      SizedBox(
                        width: 138,
                        child: _filterButton(
                          kind: CatalogFilterKind.brand,
                          selectedName: catalog.brand,
                          icon: Icons.local_offer_outlined,
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 138,
                        child: _filterButton(
                          kind: CatalogFilterKind.category,
                          selectedName: catalog.category,
                          icon: Icons.category_outlined,
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 138,
                        child: _filterButton(
                          kind: CatalogFilterKind.family,
                          selectedName: catalog.family,
                          icon: Icons.account_tree_outlined,
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 138,
                        child: _filterButton(
                          kind: CatalogFilterKind.variant,
                          selectedName: catalog.variant,
                          icon: Icons.tune,
                          enabled: catalog.familyId.isNotEmpty,
                        ),
                      ),
                    ],
                  ),
                ),
                if (catalog.familyId.isEmpty)
                  const Padding(
                    padding: EdgeInsets.only(top: 4, left: 4),
                    child: Text('Elige una familia para filtrar por variedad.'),
                  ),
              ],
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

  Widget _filterButton({
    required CatalogFilterKind kind,
    required String selectedName,
    required IconData icon,
    bool enabled = true,
  }) => OutlinedButton.icon(
    key: Key('catalog-${kind.name}-filter'),
    onPressed: enabled ? () => _openSelector(context, kind) : null,
    icon: Icon(icon),
    label: Text(
      selectedName.isEmpty
          ? _filterLabel(kind)
          : '${_filterLabel(kind)}: $selectedName',
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    ),
  );

  String _filterLabel(CatalogFilterKind kind) => switch (kind) {
    CatalogFilterKind.brand => 'Marca',
    CatalogFilterKind.category => 'Tipo',
    CatalogFilterKind.family => 'Familia',
    CatalogFilterKind.variant => 'Variedad',
  };

  Widget _catalogContent(CatalogViewModel catalog) {
    if (catalog.status == CatalogLoadStatus.loading && catalog.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if ((catalog.status == CatalogLoadStatus.unavailable ||
            catalog.status == CatalogLoadStatus.permissionDenied) &&
        catalog.items.isEmpty) {
      return _EmptyState(
        message: catalog.message ?? 'No se pudo consultar el catálogo.',
        actionLabel: catalog.status == CatalogLoadStatus.permissionDenied
            ? null
            : 'Reintentar',
        onAction: catalog.status == CatalogLoadStatus.permissionDenied
            ? null
            : catalog.refresh,
      );
    }
    if (catalog.items.isEmpty && catalog.status == CatalogLoadStatus.current) {
      return const _EmptyState(message: 'No se encontraron productos.');
    }

    return RefreshIndicator(
      onRefresh: catalog.refresh,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
        itemCount:
            catalog.items.length +
            1 +
            (catalog.status == CatalogLoadStatus.unavailable &&
                    catalog.items.isNotEmpty
                ? 1
                : 0),
        itemBuilder: (context, index) {
          final hasStaleItems =
              catalog.status == CatalogLoadStatus.unavailable &&
              catalog.items.isNotEmpty;
          if (hasStaleItems && index == 0) {
            return const Padding(
              padding: EdgeInsets.only(bottom: 12),
              child: NexaNotice(
                'No se pudo actualizar el catálogo. La información anterior puede estar desactualizada; no confirma precio, disponibilidad ni pedido.',
                isError: true,
              ),
            );
          }
          final itemIndex = index - (hasStaleItems ? 1 : 0);
          if (itemIndex == catalog.items.length) {
            return _PageControls(
              page: catalog.page,
              totalPages: catalog.totalPages,
              totalItems: catalog.totalItems,
              onPrevious: catalog.previousPage,
              onNext: catalog.nextPage,
            );
          }
          final item = catalog.items[itemIndex];
          return NexaPageWidth(
            padding: EdgeInsets.zero,
            child: _CatalogItemCard(
              item: item,
              onTap: () => context.push(
                '/catalog/${Uri.encodeComponent(item.catalogItemId)}',
              ),
            ),
          );
        },
      ),
    );
  }
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
                            title: Text(option.name),
                            trailing:
                                (widget.selectedId != null
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
  const _CatalogItemCard({required this.item, required this.onTap});

  final CatalogItemProjection item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final detail = [
      if (item.brandName != null) 'Marca: ${item.brandName}',
      if (item.categoryName != null) 'Tipo: ${item.categoryName}',
      if (item.coldChainRequirement != null)
        'Conservación: ${_coldChainLabel(item.coldChainRequirement!)}',
      if (item.productFamilyName != null &&
          item.productFamilyName != item.itemName)
        'Familia: ${item.productFamilyName}',
      if (item.productVariantName != null)
        'Variedad: ${item.productVariantName}',
      if (item.sellableAvailability case final available?)
        'Disponible: ${_quantityLabel(available)}'
            '${item.unitOfMeasure == null ? '' : ' ${item.unitOfMeasure}'}',
      item.presentation,
      if (item.skuCode != null) 'SKU ${item.skuCode}',
    ].whereType<String>().where((value) => value.isNotEmpty).join(' · ');
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            NexaProductVisual(name: item.itemName, height: 108),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.itemName,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      const Icon(Icons.chevron_right),
                    ],
                  ),
                  if (detail.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(detail, style: Theme.of(context).textTheme.bodyMedium),
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
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      if (item.unitOfMeasure case final unit?)
                        if (item.sellableAvailability == null)
                          Text('Unidad: $unit'),
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
  const _EmptyState({required this.message, this.actionLabel, this.onAction});

  final String message;
  final String? actionLabel;
  final Future<void> Function()? onAction;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
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
