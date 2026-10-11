import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

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

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<CatalogViewModel>().refresh();
    });
  }

  @override
  void dispose() {
    _searchTimer?.cancel();
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
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: TextField(
            controller: _search,
            maxLength: 120,
            textInputAction: TextInputAction.search,
            onChanged: _searchChanged,
            onSubmitted: _submitSearch,
            decoration: InputDecoration(
              hintText: 'Buscar productos',
              prefixIcon: const Icon(Icons.search),
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
        if (catalog.status == CatalogLoadStatus.loading &&
            catalog.items.isNotEmpty)
          const LinearProgressIndicator(),
        Expanded(child: _catalogContent(catalog)),
      ],
    );
  }

  Widget _catalogContent(CatalogViewModel catalog) {
    if (catalog.status == CatalogLoadStatus.loading && catalog.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (catalog.status == CatalogLoadStatus.unavailable &&
        catalog.items.isEmpty) {
      return _EmptyState(
        message: catalog.message ?? 'No se pudo consultar el catálogo.',
        actionLabel: 'Reintentar',
        onAction: catalog.refresh,
      );
    }
    if (catalog.items.isEmpty && catalog.status == CatalogLoadStatus.current) {
      return const _EmptyState(message: 'No se encontraron productos.');
    }

    return RefreshIndicator(
      onRefresh: catalog.refresh,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 20),
        itemCount: catalog.items.length + 1,
        itemBuilder: (context, index) {
          if (index == catalog.items.length) {
            return _PageControls(
              page: catalog.page,
              totalPages: catalog.totalPages,
              totalItems: catalog.totalItems,
              onPrevious: catalog.previousPage,
              onNext: catalog.nextPage,
            );
          }
          final item = catalog.items[index];
          return _CatalogItemCard(
            item: item,
            onTap: () => context.push(
              '/catalog/${Uri.encodeComponent(item.catalogItemId)}',
            ),
          );
        },
      ),
    );
  }
}

final class _CatalogItemCard extends StatelessWidget {
  const _CatalogItemCard({required this.item, required this.onTap});

  final CatalogItemProjection item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final detail = [
      item.brandName,
      item.presentation,
      if (item.skuCode != null) 'SKU ${item.skuCode}',
    ].whereType<String>().where((value) => value.isNotEmpty).join(' · ');
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.itemName,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              if (detail.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(detail, style: Theme.of(context).textTheme.bodyMedium),
              ],
              const SizedBox(height: 10),
              Wrap(
                spacing: 12,
                runSpacing: 6,
                children: [
                  Text(_availabilityLabel(item.availabilityStatus)),
                  if (item.price case final price?)
                    Text('Precio ${price.amount} ${price.currency}'),
                  if (item.unitOfMeasure case final unit?)
                    Text('Unidad: $unit'),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                'Ver detalle',
                style: Theme.of(context).textTheme.labelLarge
                    ?.copyWith(color: Theme.of(context).colorScheme.primary),
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
