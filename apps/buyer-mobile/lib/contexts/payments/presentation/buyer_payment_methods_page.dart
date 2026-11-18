import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'buyer_payment_methods_view_model.dart';

final class BuyerPaymentMethodsPage extends StatefulWidget {
  const BuyerPaymentMethodsPage({super.key});
  @override
  State<BuyerPaymentMethodsPage> createState() =>
      _BuyerPaymentMethodsPageState();
}

final class _BuyerPaymentMethodsPageState
    extends State<BuyerPaymentMethodsPage> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (mounted) context.read<BuyerPaymentMethodsViewModel>().load();
    });
  }

  @override
  Widget build(BuildContext context) => Consumer<BuyerPaymentMethodsViewModel>(
    builder: (context, model, _) => Scaffold(
      appBar: AppBar(title: const Text('Medios de pago')),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: model.load,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            children: [
              if (model.busy) const LinearProgressIndicator(),
              if (model.message case final message?)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Text(message),
                ),
              if (model.initialized && model.methods.isEmpty)
                const Text('Aún no tienes tarjetas guardadas.'),
              for (final card in model.methods)
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.credit_card),
                    title: Text('${card.brand} •••• ${card.last4}'),
                    subtitle: Text(
                      '${card.expiryMonth.toString().padLeft(2, '0')}/${card.expiryYear}${card.isDefault ? ' · Predeterminada' : ''}',
                    ),
                    trailing: model.canEdit
                        ? PopupMenuButton<String>(
                            enabled: !model.busy,
                            onSelected: (value) async {
                              if (value == 'default') {
                                await model.mutate(card.id, remove: false);
                                return;
                              }
                              final confirmed = await showDialog<bool>(
                                context: context,
                                builder: (context) => AlertDialog(
                                  title: const Text('Eliminar tarjeta'),
                                  content: const Text(
                                    '¿Deseas eliminar esta tarjeta guardada?',
                                  ),
                                  actions: [
                                    TextButton(
                                      onPressed: () =>
                                          Navigator.pop(context, false),
                                      child: const Text('Cancelar'),
                                    ),
                                    FilledButton(
                                      onPressed: () =>
                                          Navigator.pop(context, true),
                                      child: const Text('Eliminar'),
                                    ),
                                  ],
                                ),
                              );
                              if (confirmed == true) {
                                await model.mutate(card.id, remove: true);
                              }
                            },
                            itemBuilder: (_) => [
                              if (!card.isDefault)
                                const PopupMenuItem(
                                  value: 'default',
                                  child: Text('Usar como predeterminada'),
                                ),
                              const PopupMenuItem(
                                value: 'remove',
                                child: Text('Eliminar'),
                              ),
                            ],
                          )
                        : null,
                  ),
                ),
              if (model.canEdit)
                FilledButton.icon(
                  onPressed: model.initialized && !model.busy
                      ? model.setup
                      : null,
                  icon: const Icon(Icons.add),
                  label: const Text('Agregar tarjeta'),
                ),
              TextButton(
                onPressed: model.busy ? null : model.load,
                child: const Text('Actualizar'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
