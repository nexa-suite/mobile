import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'buyer_access_view_model.dart';
import '../application/buyer_access_repository.dart';

final class ContextSelectionPage extends StatelessWidget {
  const ContextSelectionPage({super.key});

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('Elige tu empresa')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              'Elige la relación de comprador que quieres usar.',
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            const SizedBox(height: 16),
            if (access.snapshot.availableContexts.isEmpty && !access.busy)
              const Text('No hay una relación de comprador disponible.'),
            for (final option in access.snapshot.availableContexts)
              _AccessContextCard(
                contextOption: option,
                busy: access.busy,
                onSelected: () => access.selectContext(option.membershipId),
              ),
            if (access.message case final message?) ...[
              const SizedBox(height: 12),
              Text(
                message,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (access.busy) ...[
              const SizedBox(height: 16),
              const Center(child: CircularProgressIndicator()),
            ],
            TextButton(
              onPressed: access.busy ? null : access.signOut,
              child: const Text('Volver al inicio de sesión'),
            ),
          ],
        ),
      ),
    );
  }
}

final class _AccessContextCard extends StatelessWidget {
  const _AccessContextCard({
    required this.contextOption,
    required this.busy,
    required this.onSelected,
  });

  final BuyerAccessContext contextOption;
  final bool busy;
  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      enabled: !busy,
      title: Text(contextOption.tenantName),
      subtitle: Text(contextOption.workspaceName),
      trailing: const Icon(Icons.chevron_right),
      onTap: onSelected,
    ),
  );
}
