import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../tenant_access_governance/application/buyer_access_repository.dart';

import 'package:go_router/go_router.dart';

import '../../../core/presentation/nexa_buyer_components.dart';

import 'purchase_request_composer_view_model.dart';

final class PurchaseRequestComposerPage extends StatefulWidget {
  const PurchaseRequestComposerPage({super.key});

  @override
  State<PurchaseRequestComposerPage> createState() =>
      _PurchaseRequestComposerPageState();
}

final class _PurchaseRequestComposerPageState
    extends State<PurchaseRequestComposerPage> {
  final _quantity = TextEditingController(text: '1');
  String? _shownQuantityVersion;

  @override
  void dispose() {
    _quantity.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final model = context.watch<PurchaseRequestComposerViewModel>();
    if (model.status == PurchaseRequestComposerStatus.loading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Nueva solicitud')),
        body: const SafeArea(
          top: false,
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }
    final item = model.item;
    if (item != null &&
        model.recoveryStatus != PurchaseRequestRecoveryStatus.none) {
      return _buildRecoveryPage(context, model, item.itemName);
    }
    if (model.status == PurchaseRequestComposerStatus.unavailable ||
        item == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Nueva solicitud')),
        body: SafeArea(
          top: false,
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    model.message ?? 'No se pudo abrir la solicitud.',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton(
                    onPressed: model.load,
                    child: const Text('Reintentar'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    final isBusy =
        model.status == PurchaseRequestComposerStatus.preparing ||
        model.status == PurchaseRequestComposerStatus.submitting;
    final hasActiveAddresses = model.activeAddresses.isNotEmpty;
    final isSubmitted = model.status == PurchaseRequestComposerStatus.submitted;
    final isReviewReady =
        model.status == PurchaseRequestComposerStatus.reviewReady;
    final draft = model.draft;
    final canOfferWalletTender = model.canOfferWalletTender;
    final effectivePaymentPreference =
        model.paymentPreference == 'WALLET' && !canOfferWalletTender
        ? null
        : model.paymentPreference;
    final canPrepareWithPaymentPreference = effectivePaymentPreference != null;
    if (draft != null && draft.lines.length == 1) {
      final version = '${draft.id}:${draft.version}';
      if (_shownQuantityVersion != version) {
        _shownQuantityVersion = version;
        final quantity = draft.lines.single.quantity;
        _quantity.value = TextEditingValue(
          text: quantity,
          selection: TextSelection.collapsed(offset: quantity.length),
        );
      }
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Nueva solicitud de compra')),
      body: SafeArea(
        top: false,
        child: NexaPageWidth(
          padding: EdgeInsets.zero,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              NexaPageIntro(
                title: item.itemName,
                description:
                    model.purchaseContext?.businessName ??
                    'Tu solicitud de compra',
                icon: Icons.shopping_bag_outlined,
              ),
              ClipRRect(
                borderRadius: BorderRadius.circular(16),
                child: NexaProductVisual(
                  name: item.itemName,
                  coldChain: item.coldChainRequirement,
                  height: 112,
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _quantity,
                enabled: !isBusy && !isSubmitted,
                onChanged: (_) => model.invalidateReview(),
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: InputDecoration(
                  labelText: 'Cantidad',
                  suffixText: item.unitOfMeasure ?? 'UNIT',
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                key: ValueKey(
                  'address:${model.selectedAddressId}:${model.activeAddresses.map((address) => address.id).join(',')}',
                ),
                isExpanded: true,
                initialValue:
                    model.activeAddresses.any(
                      (address) => address.id == model.selectedAddressId,
                    )
                    ? model.selectedAddressId
                    : null,
                decoration: const InputDecoration(
                  labelText: 'Dirección de entrega',
                ),
                items: model.activeAddresses
                    .map(
                      (address) => DropdownMenuItem(
                        value: address.id,
                        child: Text(
                          [
                            address.label,
                            address.line,
                          ].where((part) => part.isNotEmpty).join(' · '),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    )
                    .toList(growable: false),
                onChanged: isBusy || isSubmitted ? null : model.selectAddress,
              ),
              if (!hasActiveAddresses) ...[
                const SizedBox(height: 8),
                const Text(
                  'No hay direcciones de entrega activas para la cuenta Buyer.',
                ),
              ],
              const SizedBox(height: 12),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Fecha solicitada'),
                subtitle: Text(_dateLabel(model.requestedDeliveryDate)),
                trailing: const Icon(Icons.calendar_month),
                enabled: !isBusy && !isSubmitted && model.canSelectDeliveryDate,
                onTap: () async {
                  final minimumDate = model.minimumDeliveryDate;
                  final requestedDate = model.requestedDeliveryDate;
                  if (minimumDate == null || requestedDate == null) return;
                  final original = model.draft;
                  final snapshot = context
                      .read<BuyerAccessRepository>()
                      .snapshot;
                  final authority =
                      '${snapshot.authorityEpoch}:${snapshot.currentContext?.authorityFingerprint}';
                  final today = DateTime.now();
                  final lastDate = DateTime(
                    today.year + 2,
                    today.month,
                    today.day,
                  );
                  final firstSelectableDate = _dateOnly(minimumDate);
                  final lastSelectableDate = _dateOnly(lastDate);
                  if (firstSelectableDate.isAfter(lastSelectableDate)) {
                    return;
                  }

                  var initialDate = _dateOnly(requestedDate);
                  if (initialDate.isBefore(firstSelectableDate)) {
                    initialDate = firstSelectableDate;
                  } else if (initialDate.isAfter(lastSelectableDate)) {
                    initialDate = lastSelectableDate;
                  }
                  final selected = await showDatePicker(
                    context: context,
                    initialDate: initialDate,
                    firstDate: firstSelectableDate,
                    lastDate: lastSelectableDate,
                  );
                  if (selected == null ||
                      !context.mounted ||
                      !identical(original, model.draft)) {
                    return;
                  }
                  final latest = context.read<BuyerAccessRepository>().snapshot;
                  if (authority !=
                          '${latest.authorityEpoch}:${latest.currentContext?.authorityFingerprint}' ||
                      !latest.hasActiveBuyerScope) {
                    return;
                  }
                  model.selectDeliveryDate(selected);
                },
              ),
              if (model.hasOutdatedDeliveryDate) ...[
                const SizedBox(height: 4),
                Text(
                  'La fecha guardada ya no cumple el mínimo del espacio. Elige otra fecha.',
                  style: Theme.of(context).textTheme.bodySmall
                      ?.copyWith(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: ValueKey(
                  'payment:$effectivePaymentPreference:$canOfferWalletTender',
                ),
                isExpanded: true,
                initialValue: effectivePaymentPreference,
                decoration: const InputDecoration(
                  labelText: 'Preferencia de pago',
                  hintText: 'Selecciona una preferencia',
                ),
                items: [
                  DropdownMenuItem(
                    value: 'BANK_TRANSFER',
                    child: Text('Transferencia bancaria'),
                  ),
                  DropdownMenuItem(
                    value: 'CREDIT_LINE',
                    child: Text('Línea de crédito'),
                  ),
                  DropdownMenuItem(
                    value: 'CARD_STRIPE',
                    child: Text('Tarjeta'),
                  ),
                  DropdownMenuItem(value: 'CASH', child: Text('Efectivo')),
                  DropdownMenuItem(
                    value: 'CASH_ON_DELIVERY',
                    child: Text('Efectivo contra entrega'),
                  ),
                  if (canOfferWalletTender)
                    const DropdownMenuItem(
                      value: 'WALLET',
                      child: Text('Billetera'),
                    ),
                ],
                onChanged: isBusy || isSubmitted
                    ? null
                    : model.selectPaymentPreference,
              ),
              if (effectivePaymentPreference == 'WALLET') ...[
                const SizedBox(height: 8),
                const Text(
                  'Elegir Billetera expresa una preferencia. Enviar la solicitud no reserva saldo ni confirma el pago; Nexa valida y reserva el total al confirmar el pedido.',
                ),
              ],
              if (model.draft case final draft?) ...[
                const SizedBox(height: 20),
                if (!isSubmitted) ...[
                  OutlinedButton.icon(
                    onPressed: isBusy
                        ? null
                        : () => context.go(
                            '/requests/drafts/${Uri.encodeComponent(draft.id)}',
                          ),
                    icon: const Icon(Icons.playlist_add),
                    label: const Text('Agregar productos y continuar borrador'),
                  ),
                  const SizedBox(height: 12),
                ],
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          isSubmitted
                              ? 'Solicitud enviada'
                              : isReviewReady
                              ? 'Revisión del servidor'
                              : 'Borrador del servidor',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        NexaStatusBadge(draft.status),
                        if (draft.lines.isNotEmpty) ...[
                          Text(
                            'Cantidad aceptada: ${draft.lines.first.quantity} ${draft.lines.first.unit}',
                          ),
                          if (draft.lines.first.effectiveUnitPrice
                              case final price?)
                            Text(
                              'Precio unitario del servidor: $price ${draft.lines.first.currency ?? ''}'
                                  .trim(),
                            ),
                        ],
                        if (draft.paymentPreference case final payment?)
                          Text(
                            'Preferencia de pago: ${_paymentLabel(payment)}',
                          ),
                        if (draft.creditResult case final credit?)
                          Text(
                            'Resultado de crédito: ${credit.replaceAll('_', ' ')}',
                          ),
                        if (draft.routeProvider case final provider?)
                          Text(
                            'Ruta: $provider${draft.routeEstimated == true ? ' · estimada' : ''}',
                          ),
                        if (model.review?.missing case final missing?
                            when missing.isNotEmpty)
                          Text('Pendiente: ${missing.join(', ')}'),
                      ],
                    ),
                  ),
                ),
              ],
              if (model.message case final message?) ...[
                const SizedBox(height: 12),
                Text(message, textAlign: TextAlign.center),
              ],
              const SizedBox(height: 16),
              if (isBusy) const LinearProgressIndicator(),
              if (!isSubmitted)
                FilledButton.icon(
                  onPressed:
                      isBusy ||
                          !hasActiveAddresses ||
                          !canPrepareWithPaymentPreference ||
                          !model.canPrepareRequest && !isReviewReady
                      ? null
                      : isReviewReady
                      ? model.submit
                      : () => model.prepare(_quantity.text),
                  icon: Icon(isReviewReady ? Icons.send : Icons.fact_check),
                  label: Text(
                    isReviewReady ? 'Enviar solicitud' : 'Preparar revisión',
                  ),
                ),
              if (isSubmitted)
                Column(
                  children: [
                    const NexaNotice(
                      'Nexa confirmó el envío de tu solicitud. Tu proveedor revisará las condiciones antes de crear el pedido.',
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: () => context.go('/requests'),
                      icon: const Icon(Icons.shopping_bag_outlined),
                      label: const Text('Ver mis solicitudes'),
                    ),
                  ],
                ),
              const SizedBox(height: 8),
              const Text(
                'Nexa confirma precios, crédito, destino y disponibilidad al preparar y enviar la solicitud.',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildRecoveryPage(
    BuildContext context,
    PurchaseRequestComposerViewModel model,
    String itemName,
  ) {
    final isChecking =
        model.recoveryStatus == PurchaseRequestRecoveryStatus.checking;
    final isUnavailable =
        model.recoveryStatus == PurchaseRequestRecoveryStatus.unavailable;
    return Scaffold(
      appBar: AppBar(title: const Text('Revisar solicitud previa')),
      body: SafeArea(
        top: false,
        child: NexaPageWidth(
          padding: EdgeInsets.zero,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              Text(itemName, style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 16),
              if (isChecking) ...[
                const Center(child: CircularProgressIndicator()),
                const SizedBox(height: 16),
                const Text(
                  'Comprobando el estado con Nexa. No vuelvas a enviar la creación mientras termina esta comprobación.',
                  textAlign: TextAlign.center,
                ),
              ] else if (isUnavailable) ...[
                Text(
                  model.message ?? 'No se pudo leer la protección local o los borradores del servidor. La creación permanece bloqueada.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: model.load,
                  child: const Text('Reintentar comprobación'),
                ),
              ] else ...[
                if (model.recoveryCandidates.isEmpty)
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: Text(
                        'No hay borradores visibles en esta consulta. Revisa de nuevo más tarde antes de crear otra solicitud.',
                      ),
                    ),
                  )
                else
                  ...model.recoveryCandidates.map(
                    (candidate) => Card(
                      child: ListTile(
                        title: Text(
                          'Borrador ${candidate.status.toLowerCase()}',
                        ),
                        subtitle: Text(
                          'Versión ${candidate.version} · ${candidate.lineCount} productos · ${candidate.requestedDeliveryDate ?? 'sin fecha'}',
                        ),
                        trailing: const Icon(Icons.visibility_outlined),
                        onTap: () => model.resumeDraft(candidate.id),
                      ),
                    ),
                  ),
                if (model.inspectedRecoveryDraft case final inspected?) ...[
                  const SizedBox(height: 8),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Detalle del servidor · ${inspected.status}',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          Text('Versión ${inspected.version}'),
                          if (inspected.lines.isEmpty)
                            const Text(
                              'El borrador todavía no tiene productos.',
                            )
                          else
                            ...inspected.lines.map(
                              (line) => Text(
                                '${line.quantity} ${line.unit} · SKU ${line.skuId}',
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ],
                if (model.recoveryTotalPages > 1) ...[
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      TextButton.icon(
                        onPressed: model.recoveryPage > 0
                            ? () => model.refreshRecovery(
                                page: model.recoveryPage - 1,
                              )
                            : null,
                        icon: const Icon(Icons.chevron_left),
                        label: const Text('Anterior'),
                      ),
                      Text(
                        'Página ${model.recoveryPage + 1} de ${model.recoveryTotalPages}',
                      ),
                      TextButton.icon(
                        onPressed:
                            model.recoveryPage + 1 < model.recoveryTotalPages
                            ? () => model.refreshRecovery(
                                page: model.recoveryPage + 1,
                              )
                            : null,
                        icon: const Icon(Icons.chevron_right),
                        label: const Text('Siguiente'),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: 8),
                OutlinedButton(
                  onPressed: () =>
                      model.refreshRecovery(page: model.recoveryPage),
                  child: const Text('Actualizar borradores'),
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () =>
                      _confirmNewRequestAfterUncertainty(context, model),
                  child: const Text(
                    'Resolver y considerar una solicitud nueva',
                  ),
                ),
              ],
              if (model.message case final message?) ...[
                const SizedBox(height: 12),
                Text(message, textAlign: TextAlign.center),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _confirmNewRequestAfterUncertainty(
    BuildContext context,
    PurchaseRequestComposerViewModel model,
  ) async {
    final snapshot = context.read<BuyerAccessRepository>().snapshot;
    final authority =
        '${snapshot.authorityEpoch}:${snapshot.currentContext?.authorityFingerprint}';
    final originalRecovery = model.inspectedRecoveryDraft;
    final originalRecoveryStatus = model.recoveryStatus;
    final originalRecoveryCandidates = _recoveryCandidatesKey(model);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('¿Resolver la creación incierta?'),
        content: const Text(
          'El borrador anterior podría aparecer más tarde. Si continúas, deberás iniciar la nueva solicitud en otro paso y podrías terminar con dos borradores.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Volver'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Entiendo, resolver'),
          ),
        ],
      ),
    );
    if (confirmed == true &&
        context.mounted &&
        identical(originalRecovery, model.inspectedRecoveryDraft) &&
        originalRecoveryStatus == model.recoveryStatus &&
        originalRecoveryCandidates == _recoveryCandidatesKey(model)) {
      final current = context.read<BuyerAccessRepository>().snapshot;
      if (!current.hasActiveBuyerScope ||
          authority !=
              '${current.authorityEpoch}:${current.currentContext?.authorityFingerprint}') {
        return;
      }
      await model.startNewRequestAfterReview();
    }
  }
}

String _recoveryCandidatesKey(PurchaseRequestComposerViewModel model) => model
    .recoveryCandidates
    .map(
      (candidate) =>
          '${candidate.id}:${candidate.status}:${candidate.version}:'
          '${candidate.lineCount}:${candidate.requestedDeliveryDate ?? ''}',
    )
    .join('|');

String _dateLabel(DateTime? value) => value == null
    ? 'No disponible'
    : '${value.day.toString().padLeft(2, '0')}/${value.month.toString().padLeft(2, '0')}/${value.year}';

String _paymentLabel(String value) => switch (value) {
  'CREDIT_LINE' => 'Línea de crédito',
  'BANK_TRANSFER' => 'Transferencia bancaria',
  'CARD_STRIPE' => 'Tarjeta',
  'CASH' => 'Efectivo',
  'CASH_ON_DELIVERY' => 'Efectivo contra entrega',
  'WALLET' => 'Billetera',
  _ => value,
};

DateTime _dateOnly(DateTime value) =>
    DateTime(value.year, value.month, value.day);
