import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';
import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/application/evidence_upload_policy.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_receipt_draft_store.dart';
import '../application/buyer_deliveries_repository.dart';
import 'buyer_live_delivery_map.dart';
import 'buyer_deliveries_view_model.dart';

const _deliveryChangeEvents = {
  'logistics.dispatch.route-started',
  'logistics.delivery.attempt-failed',
  'logistics.delivery.continuation-created',
  'logistics.pod.completed',
  'logistics.dispatch.buyer-temperature-review',
  'logistics.dispatch.incident-recorded',
  'resync-required',
};

final class BuyerDeliveryDetailPage extends StatefulWidget {
  const BuyerDeliveryDetailPage({
    super.key,
    this.mapTileUrlTemplate = '',
    this.mapAttribution = '',
    this.scanDeliveryToken,
  });

  final String mapTileUrlTemplate;
  final String mapAttribution;
  final Future<String?> Function()? scanDeliveryToken;

  @override
  State<BuyerDeliveryDetailPage> createState() =>
      _BuyerDeliveryDetailPageState();
}

final class _BuyerDeliveryDetailPageState
    extends State<BuyerDeliveryDetailPage> {
  final _handoffCode = TextEditingController();
  final _acceptedQuantity = TextEditingController();
  final _discrepancyReason = TextEditingController();
  GoRouter? _router;
  BuyerVisibleRoutePoller? _poller;
  late final BuyerDeliveryDetailViewModel _detail;
  late final BuyerReceiptDraftStore _draftStore;
  late final BuyerAccessRepository _access;
  int _authorityRevision = 0;
  String? _lastHandoffId;
  String? _observedDraftScope;
  String? _draftAppliedAttemptId;
  String? _pendingDraftScope;
  BuyerReceiptDraft? _pendingDraft;
  Timer? _draftSaveTimer;
  BuyerReceiptDraft? _savedDraft;
  String? _draftMessage;
  int _draftGeneration = 0;
  bool _draftLoading = true;
  bool _draftRestored = false;
  bool _draftEdited = false;
  String _decision = 'DISPUTED';

  @override
  void initState() {
    super.initState();
    _detail = context.read<BuyerDeliveryDetailViewModel>();
    _draftStore = context.read<BuyerReceiptDraftStore>();
    _access = context.read<BuyerAccessRepository>();
    _authorityRevision = _detail.authorityRevision;
    _observedDraftScope = _draftScopeKey;
    _detail.addListener(_syncForm);
    unawaited(_loadReceiptDraft(_observedDraftScope));
  }

  String? get _draftScopeKey {
    final snapshot = _access.snapshot;
    if (!snapshot.hasActiveBuyerScope ||
        !snapshot.currentContext!.permissions.contains('buyer.order.read')) {
      return null;
    }
    return snapshot.currentContext!.scopeKey;
  }

  Future<void> _loadReceiptDraft(String? scopeKey) async {
    final generation = ++_draftGeneration;
    _draftLoading = scopeKey != null;
    _savedDraft = null;
    _draftMessage = null;
    if (scopeKey == null) return;
    try {
      final draft = await _draftStore.read(
        scopeKey: scopeKey,
        deliveryId: _detail.deliveryId,
      );
      if (!mounted ||
          generation != _draftGeneration ||
          scopeKey != _draftScopeKey) {
        return;
      }
      setState(() {
        _savedDraft = draft;
        _draftLoading = false;
      });
      _clearDraftIfAlreadyRecorded(scopeKey, draft);
    } catch (_) {
      if (!mounted ||
          generation != _draftGeneration ||
          scopeKey != _draftScopeKey) {
        return;
      }
      setState(() {
        _draftLoading = false;
        _draftMessage = 'No se pudo abrir el borrador local.';
      });
    }
  }

  void _clearDraftIfAlreadyRecorded(String scopeKey, BuyerReceiptDraft? draft) {
    if (draft == null ||
        !_detail.receipts.any(
          (receipt) => receipt.attemptId == draft.attemptId,
        )) {
      return;
    }
    _savedDraft = null;
    unawaited(_deleteReceiptDraft(scopeKey: scopeKey, clearMessage: false));
  }

  Future<void> _deleteReceiptDraft({
    String? scopeKey,
    bool clearMessage = true,
  }) async {
    final scope = scopeKey ?? _draftScopeKey;
    if (scope == null) return;
    final generation = ++_draftGeneration;
    try {
      await _draftStore.delete(scopeKey: scope, deliveryId: _detail.deliveryId);
      if (!mounted ||
          generation != _draftGeneration ||
          scope != _draftScopeKey) {
        return;
      }
      setState(() {
        _savedDraft = null;
        _draftRestored = false;
        _draftAppliedAttemptId = null;
        _draftEdited = false;
        _draftMessage = clearMessage ? null : _draftMessage;
      });
    } catch (_) {
      if (!mounted || generation != _draftGeneration) return;
      setState(() => _draftMessage = 'No se pudo eliminar el borrador local.');
    }
  }

  void _restoreReceiptDraft() {
    final draft = _savedDraft;
    final verified = _detail.handoff;
    if (draft == null || _draftScopeKey == null) {
      return;
    }
    if (verified != null && verified.attemptId != draft.attemptId) {
      setState(
        () => _draftMessage = 'El intento verificado cambió. Descarta el borrador antes de continuar.',
      );
      return;
    }
    setState(() {
      _decision = draft.decision;
      _acceptedQuantity.text = draft.acceptedQuantity;
      _discrepancyReason.text = draft.reason;
      _draftRestored = true;
      _draftAppliedAttemptId = draft.attemptId;
      _draftEdited = false;
      _draftMessage = 'Borrador recuperado. Aún no se ha enviado a Nexa.';
    });
  }

  void _markReceiptDraftEdited() {
    _draftEdited = true;
    _draftSaveTimer?.cancel();
    final scopeKey = _draftScopeKey;
    final handoff = _detail.handoff;
    final restoredDraft = _savedDraft;
    final canEditRestoredDraft =
        _draftRestored &&
        restoredDraft != null &&
        _draftAppliedAttemptId == restoredDraft.attemptId &&
        (handoff == null || handoff.attemptId == restoredDraft.attemptId);
    final canCreateFromVerifiedAttempt =
        handoff != null &&
        _detail.canSubmitReceipt &&
        _detail.delivery?.id == _detail.deliveryId;
    if (scopeKey == null ||
        (!canEditRestoredDraft && !canCreateFromVerifiedAttempt) ||
        _detail.receipts.any(
          (receipt) =>
              receipt.attemptId ==
              (canEditRestoredDraft
                  ? restoredDraft.attemptId
                  : handoff!.attemptId),
        ) ||
        _detail.receiptStatus == BuyerReceiptStatus.accepted ||
        _detail.receiptStatus == BuyerReceiptStatus.disputed) {
      return;
    }
    final draft = BuyerReceiptDraft(
      deliveryId: _detail.deliveryId,
      attemptId: canEditRestoredDraft
          ? restoredDraft.attemptId
          : handoff!.attemptId,
      decision: _decision,
      acceptedQuantity: _acceptedQuantity.text,
      reason: _discrepancyReason.text,
    );
    final generation = ++_draftGeneration;
    _pendingDraftScope = scopeKey;
    _pendingDraft = draft;
    _draftMessage = 'Cambios pendientes de guardar en este dispositivo.';
    _draftSaveTimer = Timer(const Duration(milliseconds: 350), () {
      unawaited(_saveReceiptDraft(scopeKey, draft, generation));
    });
  }

  Future<void> _saveRestoredDraftNow() async {
    final scopeKey = _draftScopeKey;
    final savedDraft = _savedDraft;
    if (scopeKey == null ||
        savedDraft == null ||
        !_draftRestored ||
        _draftAppliedAttemptId != savedDraft.attemptId ||
        (_detail.handoff != null &&
            _detail.handoff!.attemptId != savedDraft.attemptId)) {
      return;
    }
    _draftSaveTimer?.cancel();
    final draft = BuyerReceiptDraft(
      deliveryId: _detail.deliveryId,
      attemptId: savedDraft.attemptId,
      decision: _decision,
      acceptedQuantity: _acceptedQuantity.text,
      reason: _discrepancyReason.text,
    );
    final generation = ++_draftGeneration;
    _pendingDraftScope = scopeKey;
    _pendingDraft = draft;
    await _saveReceiptDraft(scopeKey, draft, generation);
  }

  Future<void> _saveReceiptDraft(
    String scopeKey,
    BuyerReceiptDraft draft,
    int generation,
  ) async {
    try {
      await _draftStore.write(scopeKey: scopeKey, draft: draft);
      if (_pendingDraft == draft && _pendingDraftScope == scopeKey) {
        _pendingDraft = null;
        _pendingDraftScope = null;
      }
      if (!mounted ||
          generation != _draftGeneration ||
          scopeKey != _draftScopeKey ||
          (_detail.handoff?.attemptId != draft.attemptId &&
              (!_draftRestored || _draftAppliedAttemptId != draft.attemptId))) {
        return;
      }
      setState(() {
        _savedDraft = draft;
        _draftMessage = _draftRestored
            ? 'Borrador guardado en este dispositivo. Aún no se ha enviado a Nexa.'
            : null;
      });
    } catch (_) {
      if (!mounted ||
          generation != _draftGeneration ||
          scopeKey != _draftScopeKey) {
        return;
      }
      setState(
        () => _draftMessage =
            'No se pudo guardar el borrador en este dispositivo.',
      );
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.of(context);
    if (identical(router, _router)) return;
    _poller?.dispose();
    _router = router;
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: '/deliveries/${Uri.encodeComponent(_detail.deliveryId)}',
      onRefresh: _detail.refresh,
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: BuyerDeliveriesViewModel.readPermission)
          .where((event) => _deliveryChangeEvents.contains(event.type))
          .map<void>((_) {}),
    );
  }

  void _syncForm() {
    final scopeKey = _draftScopeKey;
    if (_authorityRevision != _detail.authorityRevision) {
      _authorityRevision = _detail.authorityRevision;
      _draftSaveTimer?.cancel();
      _draftGeneration++;
      _lastHandoffId = null;
      _handoffCode.clear();
      _acceptedQuantity.clear();
      _discrepancyReason.clear();
      _decision = 'DISPUTED';
      _draftRestored = false;
      _draftAppliedAttemptId = null;
      _draftEdited = false;
      _pendingDraft = null;
      _pendingDraftScope = null;
      if (mounted) setState(() {});
    }
    if (scopeKey != _observedDraftScope) {
      _observedDraftScope = scopeKey;
      _draftSaveTimer?.cancel();
      _draftGeneration++;
      _savedDraft = null;
      _draftRestored = false;
      _draftAppliedAttemptId = null;
      _draftEdited = false;
      _pendingDraft = null;
      _pendingDraftScope = null;
      _draftLoading = true;
      unawaited(_loadReceiptDraft(scopeKey));
    }
    final handoff = _detail.handoff;
    if (handoff != null && handoff.handoffId != _lastHandoffId) {
      _lastHandoffId = handoff.handoffId;
      _handoffCode.clear();
      if (_draftRestored && _draftAppliedAttemptId != handoff.attemptId) {
        _draftRestored = false;
        _draftAppliedAttemptId = null;
        _acceptedQuantity.text = handoff.deliveredQuantity.toString();
        _discrepancyReason.clear();
        _decision = 'DISPUTED';
        _draftMessage = 'El intento verificado cambió. El borrador no se aplicó a esta recepción.';
        if (mounted) setState(() {});
      } else if (!_draftRestored) {
        _acceptedQuantity.text = handoff.deliveredQuantity.toString();
      }
    }
    final scope = _draftScopeKey;
    if (_detail.receiptStatus == BuyerReceiptStatus.accepted ||
        _detail.receiptStatus == BuyerReceiptStatus.disputed) {
      if (_savedDraft != null && scope != null) {
        _savedDraft = null;
        unawaited(_deleteReceiptDraft(scopeKey: scope));
      }
    } else if (scope != null) {
      _clearDraftIfAlreadyRecorded(scope, _savedDraft);
    }
  }

  @override
  void dispose() {
    _draftSaveTimer?.cancel();
    final pendingDraft = _pendingDraft;
    final pendingScope = _pendingDraftScope;
    if (pendingDraft != null && pendingScope != null) {
      unawaited(_flushDraftAfterDispose(pendingScope, pendingDraft));
    }
    _draftGeneration++;
    _poller?.dispose();
    _detail.removeListener(_syncForm);
    _handoffCode.dispose();
    _acceptedQuantity.dispose();
    _discrepancyReason.dispose();
    super.dispose();
  }

  Future<void> _flushDraftAfterDispose(
    String scopeKey,
    BuyerReceiptDraft draft,
  ) async {
    try {
      await _draftStore.write(scopeKey: scopeKey, draft: draft);
    } catch (_) {
      // The explicit save action reports persistence failures while mounted.
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = context.watch<BuyerDeliveryDetailViewModel>();
    if (detail.status == BuyerDeliveryDetailStatus.loading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Seguimiento de entrega')),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(child: CircularProgressIndicator()),
              ),
              const NexaNotice(
                'Consultando el estado autorizado de la entrega. Un borrador local no confirma ni envía una recepción.',
              ),
              const SizedBox(height: 12),
              _receiptDraftPanel(),
            ],
          ),
        ),
      );
    }
    final delivery = detail.delivery;
    if (delivery == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Seguimiento de entrega')),
        body: SafeArea(
          top: false,
          child: SingleChildScrollView(
            child: Column(
              children: [
                _DeliveryDetailMessage(
                  message: detail.message ?? 'Esta entrega no está disponible.',
                  showRefresh:
                      detail.status !=
                      BuyerDeliveryDetailStatus.permissionDenied,
                  onRefresh: detail.refresh,
                ),
                _receiptDraftPanel(),
              ],
            ),
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text('Pedido ${delivery.salesOrderNumber}')),
      body: SafeArea(
        top: false,
        child: RefreshIndicator(
          onRefresh: detail.refresh,
          child: NexaPageWidth(
            padding: EdgeInsets.zero,
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              children: [
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: detail.refresh,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Actualizar'),
                  ),
                ),
                if (detail.message case final message?) ...[
                  NexaNotice(message),
                  const SizedBox(height: 12),
                ],
                _receiptDraftPanel(),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        NexaStatusBadge(
                          delivery.status,
                          label: _statusLabel(delivery.status),
                        ),
                        const SizedBox(height: 12),
                        _DetailRow(
                          label: 'Destino',
                          value: _destinationLabel(delivery.destination),
                        ),
                        if (delivery.scheduledAt != null)
                          _DetailRow(
                            label: 'Programada',
                            value: _dateTimeLabel(delivery.scheduledAt)!,
                          ),
                        ExpansionTile(
                          tilePadding: EdgeInsets.zero,
                          title: const Text('Detalles de entrega'),
                          children: [
                            if (delivery.dispatchedAt != null)
                              _DetailRow(
                                label: 'Despachada',
                                value: _dateTimeLabel(delivery.dispatchedAt)!,
                              ),
                            if (delivery.deliveredAt != null)
                              _DetailRow(
                                label: 'Entregada',
                                value: _dateTimeLabel(delivery.deliveredAt)!,
                              ),
                            if (delivery.proofOfDeliveryStatus != null)
                              _DetailRow(
                                label: 'Comprobante de entrega',
                                value: _statusLabel(
                                  delivery.proofOfDeliveryStatus!,
                                ),
                              ),
                            _DetailRow(
                              label: 'Actualización',
                              value: _dateTimeLabel(delivery.updatedAt)!,
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                if (detail.hasActiveDeliveryLocationScope) ...[
                  const SizedBox(height: 12),
                  _liveLocation(context, detail),
                ],
                const SizedBox(height: 12),
                Text(
                  'Historial de entrega',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                if (detail.events.isEmpty)
                  const Padding(
                    padding: EdgeInsets.only(top: 12),
                    child: Text(
                      'Todavía no hay eventos de entrega disponibles.',
                    ),
                  ),
                for (final event in detail.events)
                  Card(
                    child: ListTile(
                      leading: Icon(
                        Icons.circle_outlined,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      title: Text(_statusLabel(event.type)),
                      subtitle: Text(_dateTimeLabel(event.occurredAt)!),
                    ),
                  ),
                const SizedBox(height: 16),
                if (detail.handoffAcceptance != null)
                  _handoffAndReceipt(context, detail)
                else if (widget.scanDeliveryToken != null)
                  _qrReceiptCard(detail, delivery),
                if (detail.handoffAcceptance == null)
                  ExpansionTile(
                    title: const Text('Hay una diferencia con mi entrega'),
                    children: [_handoffAndReceipt(context, detail)],
                  ),
                _receiptHistory(context, detail),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _qrReceiptCard(
    BuyerDeliveryDetailViewModel detail,
    BuyerDeliveryProjection delivery,
  ) {
    final enabled =
        detail.canAcceptHandoff && delivery.proofOfDeliveryStatus != 'SEALED';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  Icons.qr_code_scanner_rounded,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Recibe tu pedido',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Cuando tengas tu pedido, escanea el QR del repartidor para registrar la recepción.',
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              key: const Key('buyer-scan-delivery-qr'),
              onPressed: enabled ? () => _scanAndAccept(detail) : null,
              icon: const Icon(Icons.qr_code_scanner),
              label: const Text('Escanear y recibir'),
            ),
            if (!enabled)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'La recepción ya está registrada o todavía no está disponible.',
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _receiptDraftPanel() {
    final draft = _savedDraft;
    if (_draftLoading && draft == null && _draftMessage == null) {
      return const SizedBox.shrink();
    }
    if (draft == null && _draftMessage == null) {
      return const SizedBox.shrink();
    }
    final currentAttempt = _detail.handoff?.attemptId;
    final attemptMismatch =
        currentAttempt != null &&
        draft != null &&
        currentAttempt != draft.attemptId;
    return Card(
      key: const Key('buyer-receipt-draft-panel'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Borrador local',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (draft != null) ...[
              const SizedBox(height: 8),
              const Text(
                'Borrador local · sin conexión; no enviado ni confirmado por Nexa.',
              ),
              const SizedBox(height: 8),
              _DetailRow(
                label: 'Decisión',
                value: draft.decision == 'DISPUTED'
                    ? 'Reportar diferencia'
                    : 'Recibí todo',
              ),
              _DetailRow(
                label: 'Cantidad recibida',
                value: draft.acceptedQuantity.isEmpty
                    ? 'Sin completar'
                    : draft.acceptedQuantity,
              ),
              if (draft.reason.isNotEmpty)
                _DetailRow(label: 'Motivo', value: draft.reason),
              if (_detail.handoff == null)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                    'Verifica de nuevo el código de entrega antes de registrar la recepción. El código no se guarda en este dispositivo.',
                  ),
                ),
              if (attemptMismatch)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                    'El intento verificado cambió. Este borrador no se puede usar para el intento actual.',
                  ),
                ),
              if (_draftRestored)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                    'Borrador recuperado. Aún no se ha enviado a Nexa.',
                  ),
                ),
              if (_draftRestored && _detail.handoff == null) ...[
                const SizedBox(height: 12),
                SegmentedButton<String>(
                  key: const Key('buyer-offline-draft-decision'),
                  segments: const [
                    ButtonSegment(
                      value: 'ACCEPTED',
                      label: Text('Recibí todo'),
                    ),
                    ButtonSegment(
                      value: 'DISPUTED',
                      label: Text('Reportar diferencia'),
                    ),
                  ],
                  selected: {_decision},
                  onSelectionChanged: (selection) => setState(() {
                    _decision = selection.single;
                    _markReceiptDraftEdited();
                  }),
                ),
                const SizedBox(height: 8),
                TextField(
                  key: const Key('buyer-offline-draft-quantity'),
                  controller: _acceptedQuantity,
                  maxLength: 32,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(
                    labelText: 'Cantidad recibida',
                  ),
                  onChanged: (_) => _markReceiptDraftEdited(),
                ),
                if (_decision == 'DISPUTED') ...[
                  const SizedBox(height: 8),
                  TextField(
                    key: const Key('buyer-offline-draft-reason'),
                    controller: _discrepancyReason,
                    maxLength: 2000,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: 'Motivo de la diferencia',
                      counterText: '',
                    ),
                    onChanged: (_) => _markReceiptDraftEdited(),
                  ),
                ],
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton.icon(
                    key: const Key('buyer-save-offline-receipt-draft'),
                    onPressed: _saveRestoredDraftNow,
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('Guardar borrador'),
                  ),
                ),
              ],
            ],
            if (_draftMessage case final message?) ...[
              const SizedBox(height: 8),
              Text(message),
            ],
            if (draft != null) ...[
              const SizedBox(height: 8),
              Wrap(
                alignment: WrapAlignment.end,
                spacing: 8,
                children: [
                  OutlinedButton.icon(
                    key: const Key('buyer-restore-receipt-draft'),
                    onPressed: _draftRestored || _draftEdited || attemptMismatch
                        ? null
                        : _restoreReceiptDraft,
                    icon: const Icon(Icons.restore),
                    label: const Text('Restaurar borrador'),
                  ),
                  TextButton(
                    key: const Key('buyer-delete-receipt-draft'),
                    onPressed: () => _deleteReceiptDraft(),
                    child: const Text('Eliminar borrador'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _scanAndAccept(BuyerDeliveryDetailViewModel detail) async {
    final scan = widget.scanDeliveryToken;
    if (scan == null || !detail.canAcceptHandoff) return;
    final revision = detail.authorityRevision;
    final token = await scan();
    if (!mounted ||
        token == null ||
        revision != detail.authorityRevision ||
        !detail.canAcceptHandoff) {
      return;
    }
    await detail.acceptHandoff(token);
  }

  Widget _liveLocation(
    BuildContext context,
    BuyerDeliveryDetailViewModel detail,
  ) {
    final location = detail.currentLocation;
    final tileUrl = widget.mapTileUrlTemplate.trim();
    final attribution = widget.mapAttribution.trim();
    final tileUri = Uri.tryParse(tileUrl);
    final canRenderMap =
        tileUri != null &&
        tileUri.scheme == 'https' &&
        tileUrl.contains('{z}') &&
        tileUrl.contains('{x}') &&
        tileUrl.contains('{y}') &&
        attribution.isNotEmpty;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Ubicación de la entrega',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            if (location == null) ...[
              if (detail.locationStatus == BuyerLiveLocationStatus.loading)
                const LinearProgressIndicator(),
              if (detail.locationMessage case final message?)
                NexaNotice(
                  message,
                  isError:
                      detail.locationStatus ==
                          BuyerLiveLocationStatus.permissionDenied ||
                      detail.locationStatus ==
                          BuyerLiveLocationStatus.unavailable,
                ),
              if (detail.locationMessage == null &&
                  detail.locationStatus == BuyerLiveLocationStatus.loading)
                const Text('Consultando la última ubicación vigente…'),
            ] else ...[
              Text(
                'Ubicación recibida ${_locationAge(location.capturedAt)} · '
                'precisión ±${location.accuracyMeters.toStringAsFixed(0)} m',
              ),
              const SizedBox(height: 12),
              if (!canRenderMap)
                const NexaNotice(
                  'El proveedor de mapas no está configurado para este entorno.',
                  isError: true,
                )
              else
                SizedBox(
                  key: const Key('buyer-live-location-map'),
                  height: 240,
                  child: BuyerLiveDeliveryMap(
                    latitude: location.latitude,
                    longitude: location.longitude,
                    tileUrlTemplate: tileUrl,
                    attribution: attribution,
                  ),
                ),
              if (detail.locationMessage case final message?) ...[
                const SizedBox(height: 8),
                NexaNotice(message),
              ],
            ],
          ],
        ),
      ),
    );
  }

  Widget _handoffAndReceipt(
    BuildContext context,
    BuyerDeliveryDetailViewModel detail,
  ) {
    final locked =
        detail.receiptAttemptLocked ||
        detail.receiptStatus == BuyerReceiptStatus.accepted ||
        detail.receiptStatus == BuyerReceiptStatus.disputed;
    final working =
        detail.handoffStatus == BuyerHandoffStatus.validating ||
        detail.receiptStatus == BuyerReceiptStatus.preparing ||
        detail.receiptStatus == BuyerReceiptStatus.submitting;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Revisar una diferencia',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            const Text(
              'Verifica el código del repartidor para revisar cantidades y registrar lo que pasó.',
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('buyer-handoff-code'),
              controller: _handoffCode,
              enabled: detail.canValidateHandoff && !working && !locked,
              maxLength: 400,
              decoration: const InputDecoration(
                labelText: 'Código de entrega',
                counterText: '',
              ),
              onSubmitted: (_) => _verify(detail),
            ),
            FilledButton.icon(
              key: const Key('buyer-verify-handoff'),
              onPressed: detail.canValidateHandoff && !working && !locked
                  ? () => _verify(detail)
                  : null,
              icon: detail.handoffStatus == BuyerHandoffStatus.validating
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.verified_outlined),
              label: const Text('Verificar código'),
            ),
            if (detail.handoffMessage case final message?) ...[
              const SizedBox(height: 8),
              NexaNotice(
                message,
                isError:
                    detail.handoffStatus == BuyerHandoffStatus.invalid ||
                    detail.handoffStatus == BuyerHandoffStatus.unavailable ||
                    detail.handoffStatus == BuyerHandoffStatus.permissionDenied,
              ),
            ],
            if (detail.handoff case final handoff?) ...[
              const Divider(height: 24),
              Text(
                'Intento verificado',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              _DetailRow(
                label: 'Cantidad del conductor',
                value: handoff.deliveredQuantity.toString(),
              ),
              _DetailRow(
                label: 'Válido hasta',
                value: _dateTimeLabel(handoff.expiresAt)!,
              ),
              const SizedBox(height: 12),
              SegmentedButton<String>(
                key: const Key('buyer-receipt-decision'),
                segments: const [
                  ButtonSegment(
                    value: 'ACCEPTED',
                    label: Text('Recibí todo'),
                    icon: Icon(Icons.check_circle_outline),
                  ),
                  ButtonSegment(
                    value: 'DISPUTED',
                    label: Text('Reportar diferencia'),
                    icon: Icon(Icons.report_outlined),
                  ),
                ],
                selected: {_decision},
                onSelectionChanged: locked || working
                    ? null
                    : (selection) => setState(() {
                        _decision = selection.single;
                        _markReceiptDraftEdited();
                      }),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('buyer-received-quantity'),
                controller: _acceptedQuantity,
                enabled: !locked && !working && detail.canSubmitReceipt,
                maxLength: 32,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: const InputDecoration(
                  labelText: 'Cantidad recibida',
                ),
                onChanged: (_) => _markReceiptDraftEdited(),
              ),
              if (_decision == 'DISPUTED') ...[
                const SizedBox(height: 8),
                TextField(
                  key: const Key('buyer-discrepancy-reason'),
                  controller: _discrepancyReason,
                  enabled: !locked && !working && detail.canSubmitReceipt,
                  maxLength: 2000,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    labelText: 'Motivo de la diferencia',
                    counterText: '',
                  ),
                  onChanged: (_) => _markReceiptDraftEdited(),
                ),
                const Text(
                  'Después de registrar la diferencia, puedes adjuntar evidencia.',
                ),
              ],
              const SizedBox(height: 8),
              if (detail.receiptMessage case final message?)
                NexaNotice(
                  message,
                  isError:
                      detail.receiptStatus == BuyerReceiptStatus.unavailable ||
                      detail.receiptStatus == BuyerReceiptStatus.conflict ||
                      detail.receiptStatus ==
                          BuyerReceiptStatus.permissionDenied,
                ),
              if (detail.canRetryReceipt)
                OutlinedButton.icon(
                  key: const Key('buyer-receipt-retry'),
                  onPressed: detail.retryReceipt,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Reintentar el mismo envío'),
                )
              else
                FilledButton.icon(
                  key: const Key('buyer-submit-receipt'),
                  onPressed: detail.canSubmitReceipt && !locked && !working
                      ? () => detail.submitReceipt(
                          decision: _decision,
                          acceptedQuantity: _acceptedQuantity.text,
                          reason: _discrepancyReason.text,
                        )
                      : null,
                  icon:
                      detail.receiptStatus == BuyerReceiptStatus.submitting ||
                          detail.receiptStatus == BuyerReceiptStatus.preparing
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Icon(
                          _decision == 'DISPUTED'
                              ? Icons.report_outlined
                              : Icons.receipt_long_outlined,
                        ),
                  label: Text(
                    _decision == 'DISPUTED'
                        ? 'Registrar diferencia'
                        : 'Confirmar recepción',
                  ),
                ),
              if (detail.receipt case final receipt?) ...[
                const SizedBox(height: 8),
                _DetailRow(
                  label: 'Recepción registrada',
                  value: _dateTimeLabel(receipt.occurredAt)!,
                ),
                _DetailRow(
                  label: 'Cantidad recibida',
                  value: receipt.acceptedQuantity.toString(),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _verify(BuyerDeliveryDetailViewModel detail) =>
      detail.validateHandoff(_handoffCode.text);

  Widget _receiptHistory(
    BuildContext context,
    BuyerDeliveryDetailViewModel detail,
  ) {
    if (detail.receipts.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 16),
        Text(
          'Recepciones registradas',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        for (final receipt in detail.receipts) ...[
          const SizedBox(height: 8),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          receipt.decision == 'DISPUTED'
                              ? 'Diferencia registrada'
                              : 'Recepción confirmada',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      NexaStatusBadge(
                        receipt.decision,
                        label: receipt.decision == 'DISPUTED'
                            ? 'Diferencia'
                            : 'Aceptada',
                      ),
                    ],
                  ),
                  _DetailRow(
                    label: 'Registrada',
                    value: _dateTimeLabel(receipt.occurredAt)!,
                  ),
                  _DetailRow(
                    label: 'Cantidad recibida',
                    value: receipt.acceptedQuantity.toString(),
                  ),
                  if (receipt.recordedByMembershipId case final actor?)
                    _DetailRow(
                      label: 'Registrada por',
                      value: actor == detail.currentBuyerMembershipId
                          ? 'Tú'
                          : 'Otra persona autorizada',
                    ),
                  if (receipt.reason case final reason?) ...[
                    const SizedBox(height: 8),
                    Text('Motivo: $reason'),
                  ],
                  for (final evidence in receipt.evidence)
                    ListTile(
                      key: ValueKey('buyer-receipt-evidence-${evidence.id}'),
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(_evidenceIcon(evidence.contentType)),
                      title: Text(
                        _evidenceStatusLabel(evidence.lifecycleStatus),
                      ),
                      subtitle: Text(
                        '${_evidenceTypeLabel(evidence.contentType)} · '
                        '${_dateTimeLabel(evidence.createdAt)}\n'
                        '${detail.isEvidenceUploadedByCurrentMembership(evidence) ? 'Adjuntada por ti' : 'Adjuntada por otra persona autorizada'}',
                      ),
                    ),
                  if (receipt.decision == 'DISPUTED') ...[
                    if (detail.evidenceReceiptId == receipt.id &&
                        detail.evidenceMessage != null) ...[
                      const SizedBox(height: 8),
                      NexaNotice(
                        detail.evidenceMessage!,
                        isError:
                            detail.evidenceStatus ==
                                BuyerReceiptEvidenceStatus.unavailable ||
                            detail.evidenceStatus ==
                                BuyerReceiptEvidenceStatus.conflict ||
                            detail.evidenceStatus ==
                                BuyerReceiptEvidenceStatus.permissionDenied,
                      ),
                    ],
                    OutlinedButton.icon(
                      key: Key('buyer-receipt-evidence-${receipt.id}'),
                      onPressed:
                          detail.canAttachEvidenceTo(receipt) &&
                              !detail.isUploadingEvidence
                          ? () => _pickReceiptEvidence(detail, receipt)
                          : null,
                      icon:
                          detail.evidenceReceiptId == receipt.id &&
                              detail.isUploadingEvidence
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.attach_file),
                      label: const Text('Adjuntar evidencia'),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }

  Future<void> _pickReceiptEvidence(
    BuyerDeliveryDetailViewModel detail,
    BuyerReceiptProjection receipt,
  ) async {
    try {
      final files = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'pdf'],
      );
      if (!mounted || files.isEmpty) return;
      final file = files.single;
      final size = await file.length();
      if (size == null) {
        _showPickerMessage('No se pudo leer el archivo seleccionado.');
        return;
      }
      if (size <= 0 || size > evidenceUploadMaximumBytes) {
        _showPickerMessage('El archivo excede el límite técnico de 10 MiB.');
        return;
      }
      final filename = file.name.trim();
      final extension = filename.contains('.')
          ? filename.split('.').last.toLowerCase()
          : '';
      final contentType = switch (extension) {
        'jpg' || 'jpeg' => 'image/jpeg',
        'png' => 'image/png',
        'pdf' => 'application/pdf',
        _ => null,
      };
      if (contentType == null) {
        _showPickerMessage('Selecciona una imagen JPG, PNG o un PDF.');
        return;
      }
      final builder = BytesBuilder(copy: false);
      await for (final chunk in file.readAsByteStream()) {
        if (chunk.length > evidenceUploadMaximumBytes - builder.length) {
          _showPickerMessage('El archivo excede el límite técnico de 10 MiB.');
          return;
        }
        builder.add(chunk);
      }
      final bytes = builder.takeBytes();
      if (bytes.length != size || bytes.isEmpty) {
        bytes.fillRange(0, bytes.length, 0);
        _showPickerMessage('No se pudo leer el archivo seleccionado.');
        return;
      }
      try {
        await detail.uploadReceiptEvidence(
          receiptId: receipt.id,
          filename: filename,
          contentType: contentType,
          bytes: bytes,
        );
      } finally {
        bytes.fillRange(0, bytes.length, 0);
      }
    } catch (_) {
      if (mounted) _showPickerMessage('No se pudo seleccionar el archivo.');
    }
  }

  void _showPickerMessage(String value) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(value)));
  }
}

final class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 140,
          child: Text(label, style: Theme.of(context).textTheme.labelLarge),
        ),
        Expanded(child: Text(value)),
      ],
    ),
  );
}

final class _DeliveryDetailMessage extends StatelessWidget {
  const _DeliveryDetailMessage({
    required this.message,
    required this.showRefresh,
    required this.onRefresh,
  });

  final String message;
  final bool showRefresh;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          if (showRefresh) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: onRefresh,
              icon: const Icon(Icons.refresh),
              label: const Text('Actualizar'),
            ),
          ],
        ],
      ),
    ),
  );
}

String _statusLabel(String value) => switch (value.trim().toUpperCase()) {
  'PLANNED' => 'Programada',
  'ASSIGNED' => 'Asignada',
  'DISPATCHED' => 'Despachada',
  'IN_TRANSIT' => 'En camino',
  'PARTIAL' || 'PARTIALLY_DELIVERED' => 'Entrega parcial',
  'DELIVERED' => 'Entregada',
  'FAILED' => 'Entrega fallida',
  'CANCELLED' => 'Cancelada',
  'IN_FULFILLMENT' => 'En proceso',
  'PENDING' => 'Pendiente',
  'COMPLETED' => 'Completado',
  'CAPTURED' => 'Registrado',
  'SEALED' => 'Disponible',
  'REJECTED' => 'Rechazado',
  'HANDED_OVER' => 'Entregada al repartidor',
  'ATTEMPT_STARTED' => 'Intento de entrega iniciado',
  'ATTEMPT_FAILED' => 'Intento de entrega fallido',
  'ATTEMPT_REJECTED' => 'Entrega rechazada',
  'ATTEMPT_REFUSED' => 'Entrega rechazada por quien recibió',
  'ATTEMPT_ABSENT' => 'No había quien recibiera el pedido',
  'ATTEMPT_UNDELIVERED' => 'Entrega no realizada',
  'PROOF_OF_DELIVERY_AVAILABLE' => 'Comprobante disponible',
  _ => 'Estado no disponible',
};

String _destinationLabel(String? rawDestination) {
  final raw = rawDestination?.trim();
  if (raw == null || raw.isEmpty) return 'Dirección no disponible';

  try {
    final decoded = jsonDecode(raw);
    if (decoded is! Map) return 'Dirección no disponible';
    final address = _findAddressDetails(decoded);
    if (address == null) return 'Dirección no disponible';

    final lines = <String>[];
    final addressLine = _humanAddressPart(address['line']);
    if (addressLine != null) {
      lines.add(addressLine);
    } else {
      final streetName =
          _humanAddressPart(address['streetName']) ??
          _humanAddressPart(address['street']);
      if (streetName != null) {
        final number =
            _humanAddressPart(address['streetNumber'], allowNumber: true) ??
            _humanAddressPart(address['number'], allowNumber: true);
        final interior = _humanAddressPart(address['interior']);
        lines.add([streetName, number, interior].whereType<String>().join(' '));
      }
    }

    for (final key in const ['district', 'province', 'department']) {
      final locality = _humanAddressPart(address[key]);
      if (locality != null && !lines.contains(locality)) lines.add(locality);
    }
    if (lines.isEmpty) return 'Dirección no disponible';
    return lines.join(', ');
  } on FormatException {
    if (raw.startsWith('{') || raw.startsWith('[')) {
      return 'Dirección no disponible';
    }
    return _humanAddressPart(raw) ?? 'Dirección no disponible';
  }
}

Map<String, Object?>? _findAddressDetails(Object? value, {int depth = 0}) {
  if (value is! Map || depth > 4) return null;
  final map = <String, Object?>{
    for (final entry in value.entries)
      if (entry.key is String) entry.key as String: entry.value,
  };

  for (final key in const [
    'delivery',
    'destination',
    'destinationSnapshot',
    'addressSnapshot',
    'address',
  ]) {
    final nested = _findAddressDetails(map[key], depth: depth + 1);
    if (nested != null) return nested;
  }

  const displayKeys = {
    'line',
    'street',
    'streetName',
    'streetNumber',
    'number',
    'interior',
    'district',
    'province',
    'department',
  };
  return map.keys.any(displayKeys.contains) ? map : null;
}

String? _humanAddressPart(Object? value, {bool allowNumber = false}) {
  if (value is! String) return null;
  final text = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (text.isEmpty ||
      text.length > 180 ||
      RegExp(r'[{}\[\]<>]').hasMatch(text) ||
      RegExp(
        r'^[a-f\d]{8}-[a-f\d-]{27,}$',
        caseSensitive: false,
      ).hasMatch(text) ||
      RegExp(r'^[a-f\d]{24,}$', caseSensitive: false).hasMatch(text) ||
      RegExp(r'^[a-z\d_-]{18,}$', caseSensitive: false).hasMatch(text) ||
      RegExp(r'^https?://', caseSensitive: false).hasMatch(text)) {
    return null;
  }
  if (RegExp(r'^\d+$').hasMatch(text) && (!allowNumber || text.length > 12)) {
    return null;
  }
  return text;
}

String? _dateTimeLabel(DateTime? value) {
  if (value == null) return null;
  final local = value.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  final hour = local.hour.toString().padLeft(2, '0');
  final minute = local.minute.toString().padLeft(2, '0');
  return '$day/$month/${local.year} $hour:$minute';
}

String _locationAge(DateTime capturedAt) {
  final age = DateTime.now().toUtc().difference(capturedAt.toUtc());
  if (age.isNegative || age.inSeconds < 5) return 'recién';
  if (age.inSeconds < 60) return 'hace ${age.inSeconds} s';
  if (age.inMinutes < 60) return 'hace ${age.inMinutes} min';
  return 'hace ${age.inHours} h';
}

String _evidenceStatusLabel(String value) => switch (value) {
  'REQUESTED' || 'UPLOADING' => 'Pendiente de análisis',
  'QUARANTINED' || 'SCANNING' => 'En análisis',
  'AVAILABLE' => 'Disponible tras análisis',
  'REJECTED' => 'Rechazada por análisis',
  'DELETED' => 'Eliminada',
  _ => 'Estado no disponible',
};

String _evidenceTypeLabel(String value) => switch (value.toLowerCase()) {
  'image/jpeg' => 'Imagen JPG',
  'image/png' => 'Imagen PNG',
  'application/pdf' => 'PDF',
  _ => 'Archivo',
};

IconData _evidenceIcon(String value) => value.toLowerCase() == 'application/pdf'
    ? Icons.picture_as_pdf_outlined
    : Icons.image_outlined;
