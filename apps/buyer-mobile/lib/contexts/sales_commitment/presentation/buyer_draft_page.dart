import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../catalog_commercial_policy/application/catalog_repository.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/purchase_request_repository.dart';
import 'buyer_draft_view_model.dart';

final class BuyerDraftPage extends StatelessWidget {
  const BuyerDraftPage({super.key});
  @override
  Widget build(BuildContext context) {
    final model = context.watch<BuyerDraftViewModel>();
    final draft = model.draft;
    final canBrowse =
        context
            .watch<BuyerAccessRepository>()
            .snapshot
            .currentContext
            ?.permissions
            .contains('catalog.read') ==
        true;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Tu solicitud'),
        actions: [
          IconButton(
            tooltip: 'Consultar borrador',
            onPressed: model.busy ? null : model.load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: draft == null
            ? model.busy
                  ? const Center(child: CircularProgressIndicator())
                  : NexaEmptyState(
                      title: model.message ?? 'Borrador no disponible',
                      actionLabel: 'Consultar',
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
                        title:
                            model.purchaseContext?.businessName ??
                            'Solicitud de compra',
                        status: draft.status,
                        icon: Icons.shopping_bag_outlined,
                        subtitle:
                            '${draft.lines.length} productos · Condiciones sujetas a revisión',
                      ),
                      if (model.busy) const LinearProgressIndicator(),
                      if (model.message != null) ...[
                        NexaNotice(model.message!, isError: model.mustReload),
                        const SizedBox(height: 16),
                      ],
                      if (model.mustReload) ...[
                        FilledButton.icon(
                          onPressed: model.busy ? null : model.load,
                          icon: const Icon(Icons.refresh),
                          label: const Text('Consultar estado actual'),
                        ),
                        const SizedBox(height: 16),
                      ],
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              'Productos',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                          ),
                          if (model.canEdit && canBrowse)
                            TextButton.icon(
                              onPressed: () => _addProduct(context, model),
                              icon: const Icon(Icons.add),
                              label: const Text('Agregar'),
                            ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      for (final line in draft.lines)
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  line.presentation ??
                                      line.skuCode ??
                                      'Producto de la solicitud',
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                                if (line.skuCode != null)
                                  Text(
                                    'SKU ${line.skuCode}',
                                    style: Theme.of(context)
                                        .textTheme
                                        .bodySmall,
                                  ),
                                const SizedBox(height: 8),
                                Text('${line.quantity} ${line.unit}'),
                                if (line.effectiveUnitPrice != null)
                                  Text(
                                    '${line.effectiveUnitPrice} ${line.currency ?? ''} por unidad',
                                  ),
                                if (model.canEdit)
                                  Wrap(
                                    spacing: 8,
                                    children: [
                                      TextButton.icon(
                                        onPressed: () =>
                                            _editQuantity(context, model, line),
                                        icon: const Icon(Icons.edit_outlined),
                                        label: const Text('Cantidad'),
                                      ),
                                      if (draft.lines.length > 1)
                                        TextButton.icon(
                                          onPressed: () =>
                                              model.removeLine(line),
                                          icon: const Icon(
                                            Icons.remove_circle_outline,
                                          ),
                                          label: const Text('Quitar'),
                                        ),
                                    ],
                                  ),
                              ],
                            ),
                          ),
                        ),
                      const SizedBox(height: 24),
                      Text(
                        'Entrega y pago',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 16),
                      DropdownButtonFormField<String>(
                        key: ValueKey('address:${draft.id}:${model.addressId}'),
                        initialValue: model.addressId,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Dirección de entrega',
                        ),
                        items: [
                          for (final address
                              in model.purchaseContext?.addresses ??
                                  const <BuyerDeliveryAddressProjection>[])
                            if (address.active || address.id == model.addressId)
                              DropdownMenuItem(
                                enabled: address.active,
                                value: address.id,
                                child: Text(
                                  '${address.label} · ${address.line}${address.active ? '' : ' (inactiva)'}',
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                          if (model.addressId != null &&
                              !(model.purchaseContext?.addresses.any(
                                    (address) => address.id == model.addressId,
                                  ) ??
                                  false))
                            DropdownMenuItem(
                              value: model.addressId,
                              enabled: false,
                              child: const Text(
                                'Dirección guardada no disponible',
                              ),
                            ),
                        ],
                        onChanged: model.canEdit ? model.selectAddress : null,
                      ),
                      const SizedBox(height: 12),
                      Card(
                        child: ListTile(
                          leading: const Icon(Icons.calendar_month_outlined),
                          title: const Text('Fecha solicitada'),
                          subtitle: Text(_date(model.deliveryDate)),
                          trailing: model.canEdit
                              ? const Icon(Icons.chevron_right)
                              : null,
                          onTap: model.canEdit
                              ? () async {
                                  final today = DateTime.now();
                                  final floor = DateTime(
                                    today.year,
                                    today.month,
                                    today.day,
                                  );
                                  final original = model.draft;
                                  final selected = await showDatePicker(
                                    context: context,
                                    initialDate:
                                        model.deliveryDate == null ||
                                            model.deliveryDate!.isBefore(
                                              floor,
                                            ) ||
                                            model.deliveryDate!.isAfter(
                                              DateTime(today.year + 2, 12, 31),
                                            )
                                        ? floor
                                        : model.deliveryDate!,
                                    firstDate: floor,
                                    lastDate: DateTime(today.year + 2, 12, 31),
                                  );
                                  if (selected != null &&
                                      context.mounted &&
                                      identical(original, model.draft)) {
                                    model.selectDate(selected);
                                  }
                                }
                              : null,
                        ),
                      ),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        key: ValueKey(
                          'payment:${draft.id}:${model.paymentPreference}:${model.canOfferWallet}',
                        ),
                        initialValue: model.paymentPreference,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Preferencia de pago',
                        ),
                        items: [
                          for (final entry in _payments.entries)
                            DropdownMenuItem(
                              value: entry.key,
                              child: Text(entry.value),
                            ),
                          if (model.canOfferWallet ||
                              model.paymentPreference == 'WALLET')
                            DropdownMenuItem(
                              value: 'WALLET',
                              enabled: model.canOfferWallet,
                              child: Text(
                                model.canOfferWallet
                                    ? 'Billetera'
                                    : 'Billetera (no disponible)',
                              ),
                            ),
                          if (model.paymentPreference != null &&
                              model.paymentPreference != 'WALLET' &&
                              !_payments.containsKey(model.paymentPreference))
                            DropdownMenuItem(
                              value: model.paymentPreference,
                              enabled: false,
                              child: Text(model.paymentPreference!),
                            ),
                        ],
                        onChanged: model.canEdit ? model.selectPayment : null,
                      ),
                      if (model.paymentPreference == 'WALLET') ...[
                        const SizedBox(height: 12),
                        const NexaNotice(
                          'Enviar esta solicitud no reserva saldo. El pago se valida al confirmar el pedido.',
                        ),
                      ],
                      const SizedBox(height: 20),
                      if (model.review != null) ...[
                        NexaNotice(
                          model.review!.readyToSubmit
                              ? model.canSubmit
                                    ? 'Revisión completa. Puedes enviar tu solicitud.'
                                    : 'Revisión completa.'
                              : 'Pendiente: ${model.review!.missing.join(', ')}',
                        ),
                        const SizedBox(height: 12),
                      ],
                      if (model.isMutable) ...[
                        FilledButton.icon(
                          onPressed: model.canSubmit
                              ? model.submit
                              : model.canEdit
                              ? model.prepare
                              : null,
                          icon: Icon(
                            model.canSubmit
                                ? Icons.send_outlined
                                : Icons.fact_check_outlined,
                          ),
                          label: Text(
                            model.canSubmit
                                ? 'Enviar solicitud'
                                : 'Revisar solicitud',
                          ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'La disponibilidad, los precios y las condiciones se validan en Nexa.',
                          style: Theme.of(context).textTheme.bodySmall,
                          textAlign: TextAlign.center,
                        ),
                      ] else
                        FilledButton(
                          onPressed: () => context.go('/requests'),
                          child: const Text('Ver mis solicitudes'),
                        ),
                    ],
                  ),
                ),
              ),
      ),
    );
  }

  Future<void> _editQuantity(
    BuildContext context,
    BuyerDraftViewModel model,
    PurchaseRequestLineProjection line,
  ) async {
    final original = model.draft;
    final value = await _quantity(context, initial: line.quantity);
    if (value != null && context.mounted && identical(model.draft, original)) {
      await model.updateLine(line, value);
    }
  }

  Future<void> _addProduct(
    BuildContext context,
    BuyerDraftViewModel model,
  ) async {
    final snapshot = context.read<BuyerAccessRepository>().snapshot;
    final fingerprint =
        '${snapshot.authorityEpoch}:${snapshot.currentContext?.authorityFingerprint}';
    final product = await showModalBottomSheet<CatalogItemProjection>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => SizedBox(
        height: MediaQuery.sizeOf(context).height * .8,
        child: _CatalogPicker(
          repository: context.read<CatalogRepository>(),
          access: context.read<BuyerAccessRepository>(),
        ),
      ),
    );
    if (product == null || !context.mounted) {
      return;
    }
    final current = context.read<BuyerAccessRepository>().snapshot;
    if (fingerprint !=
        '${current.authorityEpoch}:${current.currentContext?.authorityFingerprint}') {
      return;
    }
    final original = model.draft;
    final quantity = await _quantity(context);
    if (quantity == null ||
        !context.mounted ||
        !identical(model.draft, original)) {
      return;
    }
    final latest = context.read<BuyerAccessRepository>().snapshot;
    if (fingerprint !=
        '${latest.authorityEpoch}:${latest.currentContext?.authorityFingerprint}') {
      return;
    }
    await model.addProduct(product, quantity);
  }
}

const _payments = {
  'BANK_TRANSFER': 'Transferencia bancaria',
  'CREDIT_LINE': 'Línea de crédito',
  'CARD_STRIPE': 'Tarjeta',
  'CASH': 'Efectivo',
  'CASH_ON_DELIVERY': 'Efectivo contra entrega',
};

Future<String?> _quantity(BuildContext context, {String initial = '1'}) async {
  final controller = TextEditingController(text: initial);
  final result = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Cantidad'),
      content: TextField(
        controller: controller,
        autofocus: true,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: const InputDecoration(labelText: 'Cantidad solicitada'),
        onSubmitted: (value) => Navigator.pop(context, value),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Volver'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, controller.text),
          child: const Text('Guardar'),
        ),
      ],
    ),
  );
  // Dialog transition can outlive its result; dispose after the next frame.
  WidgetsBinding.instance.addPostFrameCallback((_) => controller.dispose());
  return result;
}

String _date(DateTime? value) => value == null
    ? 'Elige una fecha'
    : '${value.day.toString().padLeft(2, '0')}/${value.month.toString().padLeft(2, '0')}/${value.year}';

final class _CatalogPicker extends StatefulWidget {
  const _CatalogPicker({required this.repository, required this.access});
  final CatalogRepository repository;
  final BuyerAccessRepository access;
  @override
  State<_CatalogPicker> createState() => _CatalogPickerState();
}

final class _CatalogPickerState extends State<_CatalogPicker> {
  final _query = TextEditingController();
  Timer? _debounce;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  late final String _authority;
  List<CatalogItemProjection> _items = const [];
  int _generation = 0;
  int _page = 0;
  int _pages = 1;
  bool _busy = false;
  bool _revoked = false;
  String? _message;
  String _fingerprint(BuyerAccessSnapshot snapshot) =>
      '${snapshot.authorityEpoch}:${snapshot.currentContext?.authorityFingerprint}';
  @override
  void initState() {
    super.initState();
    _authority = _fingerprint(widget.access.snapshot);
    _subscription = widget.access.changes.listen((snapshot) {
      if (!mounted || _fingerprint(snapshot) == _authority) {
        return;
      }
      _deauthorize();
    });
    _load(0);
  }

  bool get _hasAccess {
    final snapshot = widget.access.snapshot;
    return !_revoked &&
        snapshot.hasActiveBuyerScope &&
        _fingerprint(snapshot) == _authority &&
        snapshot.currentContext!.permissions.containsAll({
          'catalog.read',
          'buyer.sales.read',
          'buyer.sales.write',
        });
  }

  void _deauthorize() {
    _generation++;
    _debounce?.cancel();
    setState(() {
      _revoked = true;
      _items = const [];
      _busy = false;
      _message = 'El acceso cambió. Cierra esta selección.';
    });
  }

  Future<void> _load(int page) async {
    if (!mounted) {
      return;
    }
    if (!_hasAccess) {
      _deauthorize();
      return;
    }
    final generation = ++_generation;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final result = await widget.repository.list(
        query: _query.text.trim(),
        page: page,
      );
      if (!mounted || generation != _generation || !_hasAccess) {
        return;
      }
      setState(() {
        _items = result.items;
        _page = result.page;
        _pages = result.totalPages;
        _busy = false;
      });
    } on NexaApiFailure catch (error) {
      if (!mounted || generation != _generation || !_hasAccess) {
        return;
      }
      if (error.statusCode == 401) {
        widget.access.invalidateLocalSession();
        _deauthorize();
        return;
      }
      if (error.statusCode == 403) {
        _deauthorize();
        return;
      }
      setState(() {
        _busy = false;
        _items = const [];
        _message = error.userMessage;
      });
    } catch (_) {
      if (!mounted || generation != _generation || !_hasAccess) {
        return;
      }
      setState(() {
        _busy = false;
        _items = const [];
        _message = 'No se pudo consultar el catálogo.';
      });
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(16),
    child: Column(
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                'Agregar producto',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            IconButton(
              tooltip: 'Cerrar',
              onPressed: () => Navigator.pop(context),
              icon: const Icon(Icons.close),
            ),
          ],
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _query,
          enabled: _hasAccess,
          decoration: const InputDecoration(
            hintText: 'Buscar producto',
            prefixIcon: Icon(Icons.search),
          ),
          onChanged: (_) {
            _debounce?.cancel();
            _debounce = Timer(
              const Duration(milliseconds: 300),
              () => _load(0),
            );
          },
          onSubmitted: (_) {
            _debounce?.cancel();
            _load(0);
          },
        ),
        const SizedBox(height: 12),
        if (_busy) const LinearProgressIndicator(),
        if (_message != null) NexaNotice(_message!, isError: true),
        Expanded(
          child: ListView(
            children: [
              if (_items.isEmpty && !_busy && _message == null)
                const NexaEmptyState(
                  title: 'No se encontraron productos',
                  description: 'Prueba con otra búsqueda.',
                ),
              for (final item in _items)
                ListTile(
                  leading: const Icon(Icons.inventory_2_outlined),
                  title: Text(item.itemName),
                  subtitle: Text(
                    [
                      item.presentation,
                      item.skuCode,
                    ].whereType<String>().join(' · '),
                  ),
                  trailing: item.sellableSkuId == null
                      ? null
                      : const Icon(Icons.add_circle_outline),
                  enabled: !_busy && _hasAccess && item.sellableSkuId != null,
                  onTap: () {
                    if (!_busy && _hasAccess && item.sellableSkuId != null) {
                      Navigator.pop(context, item);
                    }
                  },
                ),
            ],
          ),
        ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            IconButton(
              tooltip: 'Página anterior',
              onPressed: !_hasAccess || _busy || _page == 0
                  ? null
                  : () => _load(_page - 1),
              icon: const Icon(Icons.chevron_left),
            ),
            Text('${_page + 1} de ${_pages < 1 ? 1 : _pages}'),
            IconButton(
              tooltip: 'Página siguiente',
              onPressed: !_hasAccess || _busy || _page + 1 >= _pages
                  ? null
                  : () => _load(_page + 1),
              icon: const Icon(Icons.chevron_right),
            ),
          ],
        ),
      ],
    ),
  );
  @override
  void dispose() {
    _generation++;
    _debounce?.cancel();
    _subscription.cancel();
    _query.dispose();
    super.dispose();
  }
}
