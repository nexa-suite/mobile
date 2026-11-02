import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import 'buyer_handoff_receipt_view_model.dart';

final class BuyerHandoffReceiptCard extends StatefulWidget {
  const BuyerHandoffReceiptCard({
    super.key,
    required this.onConfirmed,
    required this.scanToken,
    this.enabled = true,
  });
  final Future<void> Function() onConfirmed;
  final Future<String?> Function() scanToken;
  final bool enabled;
  @override
  State<BuyerHandoffReceiptCard> createState() =>
      _BuyerHandoffReceiptCardState();
}

final class _BuyerHandoffReceiptCardState
    extends State<BuyerHandoffReceiptCard> {
  final _code = TextEditingController();
  bool _manual = false;
  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _accept(BuyerHandoffReceiptViewModel model, String token) async {
    if (!widget.enabled) return;
    await model.accept(token);
    if (!mounted) return;
    _code.clear();
    if (model.result != null) await widget.onConfirmed();
  }

  Future<void> _scan(BuyerHandoffReceiptViewModel model) async {
    final authority = model.authorityKey;
    final token = await widget.scanToken();
    if (!mounted ||
        token == null ||
        authority != model.authorityKey ||
        !widget.enabled ||
        !model.canScan) {
      return;
    }
    await _accept(model, token);
  }

  @override
  Widget build(BuildContext context) {
    final model = context.watch<BuyerHandoffReceiptViewModel>();
    if (!model.permitted) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final canScan = widget.enabled && model.canScan;
    return Card(
      semanticContainer: false,
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  Icons.qr_code_scanner_rounded,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Recibe tu pedido',
                    style: theme.textTheme.titleLarge,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Cuando tengas tu pedido, escanea el QR del repartidor. Te mostraremos la confirmación de la entrega.',
            ),
            const SizedBox(height: 16),
            if (model.result == null)
              FilledButton.icon(
                onPressed: canScan ? () => _scan(model) : null,
                icon: model.busy
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.qr_code_scanner),
                label: Text(
                  model.busy ? 'Confirmando recepción…' : 'Escanear y recibir',
                ),
              ),
            if (!widget.enabled && model.result == null)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'Revisa la constancia o la recepción pendiente antes de volver a recibir.',
                ),
              ),
            if (canScan)
              TextButton(
                onPressed: () => setState(() => _manual = !_manual),
                child: Text(_manual ? 'Ocultar código' : 'Escribir el código'),
              ),
            if (_manual && canScan) ...[
              TextField(
                controller: _code,
                maxLength: 400,
                scrollPadding: const EdgeInsets.only(bottom: 88),
                autocorrect: false,
                enableSuggestions: false,
                decoration: const InputDecoration(
                  labelText: 'Código del repartidor',
                  counterText: '',
                ),
              ),
              const SizedBox(height: 8),
              OutlinedButton(
                onPressed: () => _accept(model, _code.text),
                child: const Text('Confirmar que recibí el pedido'),
              ),
            ],
            if (model.message case final message?) ...[
              const SizedBox(height: 12),
              NexaNotice(message, isError: model.result == null),
            ],
            if (model.result case final receipt?) ...[
              const SizedBox(height: 12),
              Text('Entrega: ${nexaStatusLabel(receipt.deliveryStatus)}'),
              Text('Constancia: ${nexaStatusLabel(receipt.podStatus)}'),
              Text('Pedido: ${nexaStatusLabel(receipt.salesOrderStatus)}'),
            ],
            if (model.canRetry)
              OutlinedButton(
                onPressed: () async {
                  await model.retry();
                  if (mounted && model.result != null) {
                    await widget.onConfirmed();
                  }
                },
                child: const Text('Reintentar la misma recepción'),
              ),
          ],
        ),
      ),
    );
  }
}
