import 'package:flutter/material.dart';
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
    if (viewModel.status == BuyerCreditExposureStatus.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (exposure == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                viewModel.message ?? 'No se pudo consultar el crédito.',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: viewModel.refresh,
                child: const Text('Reintentar'),
              ),
            ],
          ),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: viewModel.refresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Disponible',
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
              'El servidor calcula estos valores para la relación activa. La disponibilidad puede cambiar antes de confirmar una solicitud.',
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }
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
