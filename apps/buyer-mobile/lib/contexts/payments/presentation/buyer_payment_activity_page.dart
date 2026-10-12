import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

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
    return RefreshIndicator(
      onRefresh: viewModel.refreshHistory,
      child: ListView(
        key: const Key('buyer-payment-activity-list'),
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            widget.receivableNumber == null
                ? 'Cuenta por cobrar'
                : 'Cuenta ${widget.receivableNumber}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 4),
          const Text(
            'El servidor verifica el saldo pendiente y el estado de la cuenta antes de registrar un reporte.',
          ),
          const SizedBox(height: 20),
          _reportSection(viewModel),
          const SizedBox(height: 20),
          _historySection(context, viewModel),
        ],
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
          const Text(
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
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton.icon(
              key: const Key('buyer-payment-report-action'),
              onPressed: viewModel.canReport && !viewModel.isReporting
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
              child: Text(
                viewModel.reportMessage!,
                key: const Key('payment-report-result'),
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
        ],
      ),
    ),
  );

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
            Card(
              child: ListTile(
                leading: const Icon(Icons.receipt_long_outlined),
                title: Text('${payment.amount} ${payment.currency}'),
                subtitle: Text(
                  [
                    _methodLabel(payment.method),
                    _paymentStatusLabel(payment.status),
                    if (payment.reference != null)
                      'Referencia: ${payment.reference}',
                    if (payment.reviewReason != null)
                      'Observación: ${payment.reviewReason}',
                  ].join(' · '),
                ),
                isThreeLine:
                    payment.reference != null || payment.reviewReason != null,
                trailing: Text(_dateLabel(payment.createdAt)),
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

String _paymentStatusLabel(String value) => switch (value) {
  'PROCESSING' => 'Pendiente de revisión',
  'SUCCEEDED' => 'Confirmado por el servidor',
  'FAILED' => 'Fallido',
  'CANCELLED' => 'Cancelado',
  _ => value,
};

String _dateLabel(DateTime value) {
  final local = value.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  return '$day/$month/${local.year}';
}
