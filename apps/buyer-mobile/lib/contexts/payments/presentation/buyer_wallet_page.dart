import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../application/buyer_wallet_repository.dart';
import 'buyer_wallet_view_model.dart';

final class BuyerWalletPage extends StatefulWidget {
  const BuyerWalletPage({super.key});

  @override
  State<BuyerWalletPage> createState() => _BuyerWalletPageState();
}

final class _BuyerWalletPageState extends State<BuyerWalletPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerWalletViewModel>().refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<BuyerWalletViewModel>();
    final wallet = viewModel.wallet;
    return RefreshIndicator(
      onRefresh: viewModel.refresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          Text('Billetera', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 12),
          if (viewModel.canCreateRecharge ||
              viewModel.rechargeCommand != null ||
              viewModel.recharge != null ||
              viewModel.rechargeActionStatus !=
                  BuyerWalletRechargeActionStatus.idle) ...[
            _RechargeCard(viewModel: viewModel),
            const SizedBox(height: 12),
          ],
          if (viewModel.status == BuyerWalletViewStatus.loading &&
              wallet == null)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (viewModel.status == BuyerWalletViewStatus.unavailable ||
              viewModel.status == BuyerWalletViewStatus.permissionDenied)
            _UnavailableCard(
              message:
                  viewModel.message ?? 'No se pudo consultar la billetera.',
              onRetry: viewModel.refresh,
            )
          else if (wallet?.state == BuyerWalletState.notInitialized)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Billetera no inicializada'),
                    SizedBox(height: 8),
                    Text('No hay saldos disponibles para mostrar.'),
                  ],
                ),
              ),
            )
          else if (viewModel.status == BuyerWalletViewStatus.current &&
              wallet != null) ...[
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Disponible',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${wallet.availableBalance} ${wallet.currency}',
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ],
                ),
              ),
            ),
            _BalanceFact(
              title: 'Saldo contabilizado',
              amount: wallet.postedBalance!,
              currency: wallet.currency,
            ),
            _BalanceFact(
              title: 'Reservado para pedidos',
              amount: wallet.reservedBalance!,
              currency: wallet.currency,
            ),
          ],
          if (wallet != null) ...[
            const SizedBox(height: 12),
            Text('Movimientos', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            if (wallet.movements.items.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('No hay movimientos para mostrar.'),
                ),
              )
            else
              for (final movement in wallet.movements.items)
                Card(
                  child: ListTile(
                    title: Text(movement.type.replaceAll('_', ' ')),
                    subtitle: Text(_dateLabel(context, movement.occurredAt)),
                    trailing: Text(
                      '${movement.amountDelta} ${wallet.currency}',
                    ),
                  ),
                ),
            if (wallet.movements.total > wallet.movements.size)
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    tooltip: 'Página anterior',
                    onPressed: wallet.movements.page > 0
                        ? viewModel.previousPage
                        : null,
                    icon: const Icon(Icons.chevron_left),
                  ),
                  Text(
                    'Página ${wallet.movements.page + 1} de ${_pageCount(wallet.movements)}',
                  ),
                  IconButton(
                    tooltip: 'Página siguiente',
                    onPressed:
                        (wallet.movements.page + 1) * wallet.movements.size <
                            wallet.movements.total
                        ? viewModel.nextPage
                        : null,
                    icon: const Icon(Icons.chevron_right),
                  ),
                ],
              ),
          ],
        ],
      ),
    );
  }
}

final class _RechargeCard extends StatefulWidget {
  const _RechargeCard({required this.viewModel});

  final BuyerWalletViewModel viewModel;

  @override
  State<_RechargeCard> createState() => _RechargeCardState();
}

final class _RechargeCardState extends State<_RechargeCard> {
  final _amountController = TextEditingController();
  String? _clearedTerminalRechargeId;

  @override
  void dispose() {
    _amountController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = widget.viewModel;
    final busy = viewModel.isRechargeBusy;
    final recharge = viewModel.recharge;
    final action = viewModel.rechargeActionStatus;
    if (recharge != null &&
        recharge.isTerminal &&
        viewModel.rechargeCommand == null &&
        _clearedTerminalRechargeId != recharge.id) {
      _clearedTerminalRechargeId = recharge.id;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _amountController.clear();
      });
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Solicitar recarga', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            const Text(
              'El proveedor procesa el pago en su checkout seguro. Nexa actualiza el saldo '
              'solo cuando verifica la confirmación firmada del proveedor.',
            ),
            if (viewModel.canCreateRecharge) ...[
              const SizedBox(height: 12),
              TextField(
                controller: _amountController,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                enabled: !busy,
                decoration: const InputDecoration(
                  labelText: 'Monto en PEN',
                  hintText: '0.00',
                ),
              ),
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.icon(
                  onPressed: busy
                      ? null
                      : () => viewModel.requestRecharge(_amountController.text),
                  icon: const Icon(Icons.add_card_outlined),
                  label: const Text('Crear solicitud'),
                ),
              ),
            ],
            if (recharge != null) ...[
              const Divider(height: 24),
              Text('Estado vigente: ${_rechargeStatusLabel(recharge.status)}'),
              Text('Monto solicitado: ${recharge.amount} ${recharge.currency}'),
            ],
            if (viewModel.rechargeMessage case final message?) ...[
              const SizedBox(height: 8),
              Text(message),
            ],
            if (busy) ...[
              const SizedBox(height: 12),
              const LinearProgressIndicator(),
            ],
            if (!busy && viewModel.canContinueRechargeCheckout) ...[
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton.icon(
                  onPressed: viewModel.continueRechargeCheckout,
                  icon: const Icon(Icons.payment),
                  label: const Text('Continuar checkout'),
                ),
              ),
            ],
            if (!busy &&
                (viewModel.canRetryRecharge || viewModel.canRefreshRecharge)) ...[
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton.icon(
                  onPressed: viewModel.canRetryRecharge
                      ? viewModel.retryRechargeCreation
                      : viewModel.refreshRechargeStatus,
                  icon: const Icon(Icons.refresh),
                  label: Text(
                    viewModel.canRetryRecharge
                        ? 'Reintentar la misma solicitud'
                        : 'Consultar estado vigente',
                  ),
                ),
              ),
            ],
            if (action == BuyerWalletRechargeActionStatus.conflict) ...[
              const SizedBox(height: 8),
              const Text(
                'La solicitud conserva su clave de idempotencia para evitar duplicados. '
                'No se puede iniciar otra con esta identidad hasta resolver el conflicto.',
              ),
            ],
          ],
        ),
      ),
    );
  }
}

String _rechargeStatusLabel(BuyerWalletRechargeStatus status) => switch (status) {
  BuyerWalletRechargeStatus.preparing => 'Preparando',
  BuyerWalletRechargeStatus.awaitingPayment => 'Pago pendiente',
  BuyerWalletRechargeStatus.succeeded => 'Confirmada por Nexa',
  BuyerWalletRechargeStatus.failed => 'No completada',
  BuyerWalletRechargeStatus.cancelled => 'Cancelada',
  BuyerWalletRechargeStatus.rejected => 'Rechazada',
};

final class _BalanceFact extends StatelessWidget {
  const _BalanceFact({
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

final class _UnavailableCard extends StatelessWidget {
  const _UnavailableCard({required this.message, required this.onRetry});

  final String message;
  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(message),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('Reintentar'),
            ),
          ),
        ],
      ),
    ),
  );
}

int _pageCount(BuyerWalletMovementsPageProjection page) =>
    (page.total + page.size - 1) ~/ page.size;

String _dateLabel(BuildContext context, DateTime value) {
  final local = value.toLocal();
  final date = MaterialLocalizations.of(context).formatMediumDate(local);
  final time = MaterialLocalizations.of(context)
      .formatTimeOfDay(TimeOfDay.fromDateTime(local));
  return '$date · $time';
}
