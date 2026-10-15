import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import 'buyer_access_view_model.dart';
import '../application/buyer_access_repository.dart';

final class ContextSelectionPage extends StatelessWidget {
  const ContextSelectionPage({super.key});

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('Contexto de compra')),
      body: SafeArea(
        child: NexaPageWidth(
          padding: EdgeInsets.zero,
          child: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
            children: [
              const NexaPageIntro(
                title: 'Elige tu empresa',
                description:
                    'Selecciona la relación de comprador para esta sesión.',
                icon: Icons.business_outlined,
              ),
              if (access.snapshot.availableContexts.isEmpty && !access.busy)
                const NexaEmptyState(
                  title: 'Sin contexto disponible',
                  description: 'No hay una relación de comprador disponible.',
                ),
              for (final option in access.snapshot.availableContexts)
                _AccessContextCard(
                  contextOption: option,
                  busy: access.busy,
                  onSelected: () => access.selectContext(option.membershipId),
                ),
              if (access.message case final message?) ...[
                const SizedBox(height: 12),
                NexaNotice(message, isError: true),
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
      leading: Icon(
        Icons.business_outlined,
        color: Theme.of(context).colorScheme.primary,
      ),
      title: Text(contextOption.tenantName),
      subtitle: Text(contextOption.workspaceName),
      trailing: const Icon(Icons.chevron_right),
      onTap: onSelected,
    ),
  );
}
