import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'buyer_credit_exposure_view_model.dart';

final class BuyerCreditExposurePage extends StatefulWidget {
  const BuyerCreditExposurePage({super.key});

  @override
  State<BuyerCreditExposurePage> createState() =>
      _BuyerCreditExposurePageState();
}

final class _BuyerCreditExposurePageState
    extends State<BuyerCreditExposurePage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerCreditExposureViewModel>().refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<BuyerCreditExposureViewModel>();
    final exposure = viewModel.exposure;
    return RefreshIndicator(
      onRefresh: viewModel.refresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          if (viewModel.status == BuyerCreditExposureStatus.loading &&
              exposure == null)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            ),
          if (exposure == null &&
              viewModel.status != BuyerCreditExposureStatus.loading)
            _UnavailableCard(
              message: viewModel.message ?? 'No se pudo consultar el crédito.',
              onRetry: viewModel.refresh,
            ),
          if (exposure != null) ...[
            Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Crédito disponible',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${exposure.availableCredit} ${exposure.currency}',
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(exposure.active ? 'Cuenta activa' : 'Cuenta inactiva'),
                    if (exposure.asOf != null)
                      Text('Actualizado ${_dateLabel(exposure.asOf!)}'),
                  ],
                ),
              ),
            ),
            _CreditFact(
              title: 'Límite de crédito',
              amount: exposure.creditLimit,
              currency: exposure.currency,
            ),
            _CreditFact(
              title: 'Exposición usada',
              amount: exposure.used,
              currency: exposure.currency,
            ),
            _CreditFact(
              title: 'Exposición contable',
              amount: exposure.ledgerExposure,
              currency: exposure.currency,
            ),
            _CreditFact(
              title: 'Cuentas por cobrar',
              amount: exposure.outstandingReceivables,
              currency: exposure.currency,
            ),
            _CreditFact(
              title: 'Reservas activas',
              amount: exposure.reservedExposure,
              currency: exposure.currency,
            ),
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Text(
                'Estos importes los calcula el servidor para la relación activa. El crédito disponible no es un saldo de dinero ni una confirmación de compra.',
                textAlign: TextAlign.center,
              ),
            ),
          ],
          const Divider(height: 32),
          Text(
            'Cuentas por cobrar',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          if (viewModel.receivablesStatus == BuyerReceivablesStatus.loading &&
              viewModel.receivables.isEmpty)
            const Padding(
              padding: EdgeInsets.all(20),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (viewModel.receivablesStatus ==
                  BuyerReceivablesStatus.permissionDenied ||
              viewModel.receivablesStatus == BuyerReceivablesStatus.unavailable)
            _UnavailableCard(
              message:
                  viewModel.receivablesMessage ??
                  'No se pudieron consultar tus cuentas por cobrar.',
              onRetry: viewModel.refresh,
            )
          else if (viewModel.receivablesStatus ==
                  BuyerReceivablesStatus.current &&
              viewModel.receivables.isEmpty)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text('No hay cuentas por cobrar para mostrar.'),
              ),
            )
          else
            for (final receivable in viewModel.receivables)
              Card(
                child: ListTile(
                  title: Text(receivable.number),
                  subtitle: Text(
                    '${receivable.status} · Pendiente ${receivable.remaining} ${receivable.currency}',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => context.push(
                    '/credit/receivables/${receivable.id}/payments',
                    extra: receivable,
                  ),
                ),
              ),
          if (viewModel.receivablesTotal > viewModel.receivablesSize)
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  tooltip: 'Página anterior',
                  onPressed: viewModel.receivablesPage > 0
                      ? viewModel.previousReceivablesPage
                      : null,
                  icon: const Icon(Icons.chevron_left),
                ),
                Text(
                  'Página ${viewModel.receivablesPage + 1} de ${((viewModel.receivablesTotal + viewModel.receivablesSize - 1) ~/ viewModel.receivablesSize)}',
                ),
                IconButton(
                  tooltip: 'Página siguiente',
                  onPressed:
                      (viewModel.receivablesPage + 1) *
                              viewModel.receivablesSize <
                          viewModel.receivablesTotal
                      ? viewModel.nextReceivablesPage
                      : null,
                  icon: const Icon(Icons.chevron_right),
                ),
              ],
            ),
        ],
      ),
    );
  }
}

final class _UnavailableCard extends StatelessWidget {
  const _UnavailableCard({required this.message, required this.onRetry});

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: onRetry, child: const Text('Reintentar')),
        ],
      ),
    ),
  );
}

final class _CreditFact extends StatelessWidget {
  const _CreditFact({
    required this.title,
    required this.amount,
    required this.currency,
  });

  final String title;
  final String amount;
  final String currency;

  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(title: Text(title), trailing: Text('$amount $currency')),
  );
}

String _dateLabel(DateTime value) {
  final local = value.toLocal();
  final day = local.day.toString().padLeft(2, '0');
  final month = local.month.toString().padLeft(2, '0');
  return '$day/$month/${local.year}';
}
