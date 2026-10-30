import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/buyer_addresses_repository.dart';
import 'buyer_addresses_view_model.dart';
import 'buyer_address_editor.dart';

class BuyerAddressesPage extends StatefulWidget {
  const BuyerAddressesPage({super.key, this.chooseLocation});
  final BuyerAddressLocationPicker? chooseLocation;
  @override
  State<BuyerAddressesPage> createState() => _BuyerAddressesPageState();
}

class _BuyerAddressesPageState extends State<BuyerAddressesPage> {
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (mounted) context.read<BuyerAddressesViewModel>().load();
    });
  }

  @override
  Widget build(BuildContext context) => Consumer<BuyerAddressesViewModel>(
    builder: (context, vm, _) => Scaffold(
      appBar: AppBar(
        title: const Text('Mis direcciones'),
        actions: [
          IconButton(
            tooltip: 'Actualizar direcciones',
            onPressed: vm.busy ? null : vm.load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 680),
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                const Text(
                  'Guarda tus direcciones personales. Las direcciones de tu empresa se administran por separado.',
                ),
                const SizedBox(height: 16),
                if (vm.busy) const LinearProgressIndicator(),
                if (vm.message case final message?)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: NexaNotice(message, isError: vm.messageIsError),
                  ),
                if (vm.mustReload || (!vm.loaded && !vm.busy))
                  OutlinedButton.icon(
                    onPressed: vm.busy ? null : vm.load,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Actualizar direcciones'),
                  ),
                if (vm.loaded && vm.items.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 32),
                    child: Text(
                      'Aún no has guardado una dirección. Agrega la primera para encontrarla aquí.',
                    ),
                  ),
                for (final address in vm.items)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Icon(Icons.location_on_outlined),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  address.label,
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium,
                                ),
                              ),
                            ],
                          ),
                          if (address.isDefault)
                            const Chip(
                              label: Text('Predeterminada'),
                              avatar: Icon(Icons.check_circle_outline),
                            ),
                          const SizedBox(height: 8),
                          Text(address.text('line')),
                          if (address.text('reference').isNotEmpty)
                            Text(address.text('reference')),
                          if (address.text('recipientName').isNotEmpty)
                            Text('Recibe: ${address.text('recipientName')}'),
                          const SizedBox(height: 12),
                          Wrap(
                            spacing: 8,
                            runSpacing: 8,
                            children: [
                              OutlinedButton.icon(
                                onPressed: vm.canEdit
                                    ? () => _edit(vm, address)
                                    : null,
                                icon: const Icon(Icons.edit_outlined),
                                label: const Text('Editar'),
                              ),
                              if (!address.isDefault)
                                TextButton(
                                  onPressed: vm.canEdit
                                      ? () => vm.makeDefault(address)
                                      : null,
                                  child: const Text('Usar como principal'),
                                ),
                              TextButton(
                                onPressed: vm.canEdit
                                    ? () => _remove(vm, address)
                                    : null,
                                child: const Text('Quitar'),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: vm.canEdit ? () => _edit(vm, null) : null,
                  icon: const Icon(Icons.add_location_alt_outlined),
                  label: const Text('Agregar dirección'),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
  Future<void> _edit(BuyerAddressesViewModel vm, BuyerAddress? address) async {
    final scope = vm.scope;
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => ChangeNotifierProvider.value(
          value: vm,
          child: BuyerAddressEditor(
            address: address,
            scope: scope,
            chooseLocation: widget.chooseLocation,
          ),
        ),
      ),
    );
  }

  Future<void> _remove(BuyerAddressesViewModel vm, BuyerAddress address) async {
    final scope = vm.scope;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('¿Quitar dirección?'),
        content: Text(
          '“${address.label}” dejará de aparecer en tus direcciones.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Conservar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Quitar'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted && vm.scope == scope) {
      await vm.remove(address);
    }
  }
}
