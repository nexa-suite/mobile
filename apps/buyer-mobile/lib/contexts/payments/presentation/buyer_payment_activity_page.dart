import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/application/evidence_upload_policy.dart';
import '../application/buyer_payments_repository.dart';
import 'buyer_payment_activity_view_model.dart';

final class BuyerPaymentActivityPage extends StatefulWidget {
  const BuyerPaymentActivityPage({super.key, this.receivableNumber});

  final String? receivableNumber;

  @override
  State<BuyerPaymentActivityPage> createState() =>
      _BuyerPaymentActivityPageState();
}

final class _BuyerPaymentActivityPageState
    extends State<BuyerPaymentActivityPage> {
  final _referenceController = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerPaymentActivityViewModel>().loadHistory(0);
    });
  }

  @override
  void dispose() {
    _referenceController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<BuyerPaymentActivityViewModel>();
    return SafeArea(
      child: RefreshIndicator(
        onRefresh: viewModel.refreshHistory,
        child: NexaPageWidth(
          padding: EdgeInsets.zero,
          child: ListView(
            key: const Key('buyer-payment-activity-list'),
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            children: [
              NexaPageIntro(
                title: widget.receivableNumber == null
                    ? 'Cuenta por cobrar'
                    : 'Cuenta ${widget.receivableNumber}',
                description: 'El servidor verifica el saldo y el estado antes de registrar un reporte.',
                icon: Icons.payments_outlined,
              ),
              _reportSection(viewModel),
              const SizedBox(height: 20),
              _historySection(context, viewModel),
            ],
          ),
        ),
      ),
    );
  }

  Widget _reportSection(BuyerPaymentActivityViewModel viewModel) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Reportar transferencia',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          const NexaNotice(
            'Ingresa la referencia bancaria. Esto informa al equipo; no confirma que el pago haya sido aplicado.',
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _referenceController,
            maxLength: 160,
            enabled: viewModel.canReport && !viewModel.isReporting,
            decoration: const InputDecoration(
              labelText: 'Referencia de transferencia',
              hintText: 'Referencia bancaria',
            ),
          ),
          OutlinedButton.icon(
            key: const Key('buyer-transfer-proof-pick'),
            onPressed:
                viewModel.canReport &&
                    !viewModel.isReporting &&
                    !viewModel.isPreparingProof
                ? () => _pickProof(viewModel)
                : null,
            icon: const Icon(Icons.attach_file),
            label: const Text('Adjuntar comprobante (opcional)'),
          ),
          if (viewModel.proofFilename != null) ...[
            Text(viewModel.proofFilename!),
            TextButton(
              onPressed: !viewModel.isReporting && !viewModel.isPreparingProof
                  ? viewModel.removeProof
                  : null,
              child: const Text('Retirar del reporte'),
            ),
            if (viewModel.proof != null && !viewModel.proof!.isRejected)
              TextButton.icon(
                key: const Key('buyer-transfer-proof-refresh'),
                onPressed: !viewModel.isReporting && !viewModel.isPreparingProof
                    ? viewModel.refreshProof
                    : null,
                icon: const Icon(Icons.refresh),
                label: const Text('Actualizar estado del comprobante'),
              ),
          ],
          if (viewModel.proofMessage != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: NexaNotice(viewModel.proofMessage!),
            ),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton.icon(
              key: const Key('buyer-payment-report-action'),
              onPressed: viewModel.canSubmitReport
                  ? () async {
                      await viewModel.reportBankTransfer(
                        _referenceController.text,
                      );
                      if (mounted &&
                          viewModel.reportStatus ==
                              BuyerTransferReportStatus.reported) {
                        _referenceController.clear();
                      }
                    }
                  : null,
              icon: viewModel.isReporting
                  ? const SizedBox.square(
                      dimension: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.send_outlined),
              label: Text(
                viewModel.isReporting ? 'Enviando…' : 'Reportar transferencia',
              ),
            ),
          ),
          if (!viewModel.canReport)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text('No tienes permiso para reportar transferencias.'),
            ),
          if (viewModel.reportMessage != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: NexaNotice(
                viewModel.reportMessage!,
                key: const Key('payment-report-result'),
              ),
            ),
        ],
      ),
    ),
  );

  Future<void> _pickProof(BuyerPaymentActivityViewModel viewModel) async {
    final selectionLease = viewModel.proofSelectionLease;
    if (selectionLease == null) return;
    try {
      final files = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'pdf'],
      );
      if (!mounted || files.isEmpty) return;
      final file = files.single;
      final size = await file.length();
      if (size == null || size <= 0 || size > evidenceUploadMaximumBytes) {
        _pickerMessage('Selecciona un archivo de hasta 10 MiB.');
        return;
      }
      final contentType = switch (file.name.split('.').last.toLowerCase()) {
        'jpg' || 'jpeg' => 'image/jpeg',
        'png' => 'image/png',
        'pdf' => 'application/pdf',
        _ => null,
      };
      if (contentType == null) {
        _pickerMessage('Selecciona una imagen JPG, PNG o un PDF.');
        return;
      }
      final builder = BytesBuilder(copy: false);
      await for (final chunk in file.readAsByteStream()) {
        if (chunk.length > evidenceUploadMaximumBytes - builder.length) {
          _pickerMessage('El archivo excede el límite de 10 MiB.');
          return;
        }
        builder.add(chunk);
      }
      final bytes = builder.takeBytes();
      try {
        if (!mounted || bytes.length != size) return;
        await viewModel.uploadTransferProof(
          filename: file.name,
          contentType: contentType,
          bytes: bytes,
          selectionLease: selectionLease,
        );
      } finally {
        bytes.fillRange(0, bytes.length, 0);
      }
    } catch (_) {
      _pickerMessage('No se pudo leer el archivo seleccionado.');
    }
  }

  void _pickerMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Widget _historySection(
    BuildContext context,
    BuyerPaymentActivityViewModel viewModel,
  ) {
    final history = viewModel.history;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                'Historial de pagos',
                key: const Key('buyer-payment-history-title'),
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            IconButton(
              tooltip: 'Actualizar historial',
              onPressed:
                  viewModel.canRead &&
                      viewModel.historyStatus !=
                          BuyerPaymentHistoryStatus.loading
                  ? viewModel.refreshHistory
                  : null,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        if (viewModel.historyStatus == BuyerPaymentHistoryStatus.loading)
          const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator(),
            ),
          )
        else if (history == null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(
              viewModel.historyMessage ??
                  (viewModel.canRead
                      ? 'No se pudo consultar el historial.'
                      : 'No tienes permiso para consultar el historial de pagos.'),
            ),
          )
        else if (history.items.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text('Aún no hay reportes ni pagos para esta cuenta.'),
          )
        else ...[
          for (final payment in history.items)
            NexaRecordCard(
              title: '${payment.amount} ${payment.currency}',
              status: payment.status,
              icon: Icons.receipt_long_outlined,
              subtitle:
                  '${_methodLabel(payment.method)} · ${_dateLabel(payment.createdAt)}',
              footer:
                  (payment.reference == null && payment.reviewReason == null)
                  ? null
                  : Text(
                      [
                        if (payment.reference != null)
                          'Referencia: ${payment.reference}',
                        if (payment.reviewReason != null)
                          'Observación: ${payment.reviewReason}',
                      ].join(' · '),
                    ),
            ),
          if (history.total > history.size)
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  tooltip: 'Página anterior',
                  onPressed: history.page > 0
                      ? viewModel.previousHistoryPage
                      : null,
                  icon: const Icon(Icons.chevron_left),
                ),
                Text('Página ${history.page + 1} de ${_pageCount(history)}'),
                IconButton(
                  tooltip: 'Página siguiente',
                  onPressed: (history.page + 1) * history.size < history.total
                      ? viewModel.nextHistoryPage
                      : null,
                  icon: const Icon(Icons.chevron_right),
                ),
              ],
            ),
        ],
      ],
    );
  }
}

int _pageCount(BuyerPaymentHistoryPageProjection page) =>
    (page.total / page.size).ceil();

String _methodLabel(String value) => switch (value) {
  'BANK_TRANSFER' => 'Transferencia bancaria',
  'CREDIT_LINE' => 'Línea de crédito',
  'CARD' => 'Tarjeta',
  _ => value,
};

String _dateLabel(DateTime value) {
  final local = value.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  return '$day/$month/${local.year}';
}
