import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/presentation/nexa_buyer_theme.dart';
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
                description: 'Continúa con la empresa y el espacio de compra que necesitas.',
                icon: Icons.business_outlined,
              ),
              if (access.snapshot.availableContexts.isEmpty && !access.busy)
                const NexaEmptyState(
                  title: 'Sin contexto disponible',
                  description: 'No hay una relación de comprador disponible.',
                ),
              if (access.snapshot.availableContexts.isNotEmpty) ...[
                Text(
                  'EMPRESAS DISPONIBLES',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  'Tus pedidos y documentos se mostrarán dentro del contexto que elijas.',
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
                const SizedBox(height: 16),
                LayoutBuilder(
                  builder: (context, constraints) {
                    final columns = constraints.maxWidth >= 600 ? 2 : 1;
                    final width =
                        (constraints.maxWidth - (columns - 1) * 16) / columns;
                    return Wrap(
                      spacing: 16,
                      runSpacing: 16,
                      children: [
                        for (final option in access.snapshot.availableContexts)
                          SizedBox(
                            width: width,
                            child: _AccessContextCard(
                              contextOption: option,
                              busy: access.busy,
                              onSelected: () =>
                                  access.selectContext(option.membershipId),
                            ),
                          ),
                      ],
                    );
                  },
                ),
              ],
              if (access.message case final message?) ...[
                const SizedBox(height: 12),
                NexaNotice(message, isError: true),
              ],
              if (access.busy) ...[
                const SizedBox(height: 16),
                const LinearProgressIndicator(
                  semanticsLabel: 'Abriendo contexto de compra',
                ),
                const SizedBox(height: 8),
                const Text('Preparando tu espacio de compra…'),
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
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final tokens =
        theme.extension<NexaThemeExtension>() ?? NexaBuyerTheme.tokens;
    final radius = BorderRadius.circular(tokens.radiusCard);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: radius,
        side: BorderSide(color: colors.outlineVariant),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        borderRadius: radius,
        onTap: busy ? null : onSelected,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ExcludeSemantics(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: colors.primaryContainer,
                    borderRadius: BorderRadius.circular(tokens.radiusControl),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Icon(
                      Icons.business_outlined,
                      color: colors.onPrimaryContainer,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      contextOption.tenantName,
                      style: theme.textTheme.titleMedium,
                    ),
                    const SizedBox(height: 4),
                    Text(
                      contextOption.workspaceName,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            'Continuar',
                            style: theme.textTheme.labelLarge?.copyWith(
                              color: busy
                                  ? colors.onSurfaceVariant
                                  : colors.primary,
                            ),
                          ),
                        ),
                        ExcludeSemantics(
                          child: Icon(
                            Icons.arrow_forward,
                            size: 20,
                            color: busy
                                ? colors.onSurfaceVariant
                                : colors.primary,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
