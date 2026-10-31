import 'package:flutter/material.dart';

import '../contexts/credit_receivables/application/credit_exposure_repository.dart';
import '../core/presentation/nexa_buyer_components.dart';
import '../core/presentation/nexa_action_surface.dart';
import '../core/presentation/nexa_buyer_palette.dart';
import '../core/presentation/nexa_display_format.dart';

class BuyerHomeGreeting extends StatelessWidget {
  const BuyerHomeGreeting({super.key, this.name, this.onFavorites});
  final String? name;
  final VoidCallback? onFavorites;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(
          name?.trim().isNotEmpty == true
              ? 'Hola, ${name!.trim()}'
              : 'Tus compras',
          style: Theme.of(context).textTheme.headlineMedium,
        ),
      ),
      if (onFavorites != null)
        IconButton.filledTonal(
          tooltip: 'Mis favoritos',
          onPressed: onFavorites,
          style: IconButton.styleFrom(
            backgroundColor: NexaBuyerPalette.primarySurface,
          ),
          icon: const Icon(Icons.favorite_outline),
        ),
    ],
  );
}

class BuyerOrdersWidget extends StatelessWidget {
  const BuyerOrdersWidget({
    super.key,
    this.total,
    this.code,
    this.status,
    this.createdAt,
    this.amount,
    this.currency,
    this.refreshing = false,
    required this.message,
    required this.onOpen,
    this.onLatest,
  });
  final int? total;
  final String? code;
  final String? status;
  final String? createdAt;
  final num? amount;
  final String? currency;
  final bool refreshing;
  final String message;
  final VoidCallback onOpen;
  final VoidCallback? onLatest;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Tus pedidos', style: theme.textTheme.titleLarge),
                      if (total != null)
                        Text(
                          refreshing
                              ? '$total activos · Actualizando…'
                              : '$total activos',
                          style: theme.textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                TextButton(onPressed: onOpen, child: const Text('Ver todos')),
              ],
            ),
            if (code == null)
              Text(message, style: theme.textTheme.bodySmall)
            else ...[
              const SizedBox(height: 8),
              NexaActionSurface(
                color: NexaBuyerPalette.surface,
                onTap: onLatest ?? onOpen,
                radius: 10,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              code!,
                              style: theme.textTheme.titleMedium,
                            ),
                          ),
                          const Icon(
                            Icons.chevron_right,
                            color: NexaBuyerPalette.primary,
                            size: 20,
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 12,
                        runSpacing: 8,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          if (status != null) NexaStatusBadge(status!),
                          if (amount != null && currency?.isNotEmpty == true)
                            Text(
                              nexaMoneyLabel(amount!.toString(), currency),
                              style: theme.textTheme.titleSmall,
                            ),
                          if (DateTime.tryParse(createdAt ?? '') != null)
                            Text(
                              nexaDateLabel(createdAt),
                              style: theme.textTheme.bodySmall,
                            ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class BuyerCreditWidget extends StatelessWidget {
  const BuyerCreditWidget({
    super.key,
    this.exposure,
    this.refreshing = false,
    required this.message,
    required this.onOpen,
  });
  final BuyerCreditExposureProjection? exposure;
  final bool refreshing;
  final String message;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final credit = exposure;
    final available = num.tryParse(credit?.availableCredit ?? '');
    final limit = num.tryParse(credit?.creditLimit ?? '');
    final canPlot =
        credit?.active == true &&
        available != null &&
        available.isFinite &&
        available >= 0 &&
        limit != null &&
        limit.isFinite &&
        limit > 0;
    final surface = credit?.active == true
        ? NexaBuyerPalette.positive
        : NexaBuyerPalette.ink;
    final supporting = credit?.active == true ? Colors.white : Colors.white70;
    return _HomeSurface(
      color: surface,
      onTap: onOpen,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.account_balance_wallet_outlined,
                color: Colors.white,
                size: 20,
              ),
              const Spacer(),
              Icon(Icons.north_east, color: supporting, size: 18),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            refreshing ? 'Crédito · Actualizando…' : 'Crédito disponible',
            style: theme.textTheme.labelMedium?.copyWith(color: supporting),
          ),
          const SizedBox(height: 4),
          Text(
            credit == null
                ? '—'
                : nexaMoneyLabel(credit.availableCredit, credit.currency),
            style: theme.textTheme.headlineLarge?.copyWith(color: Colors.white),
          ),
          const SizedBox(height: 8),
          if (canPlot) ...[
            Semantics(
              label:
                  'Disponible ${nexaMoneyLabel(credit!.availableCredit, credit.currency)} de ${nexaMoneyLabel(credit.creditLimit, credit.currency)}',
              child: ExcludeSemantics(
                child: LinearProgressIndicator(
                  value: (available / limit).clamp(0.0, 1.0).toDouble(),
                  minHeight: 5,
                  borderRadius: BorderRadius.circular(4),
                  color: Colors.white,
                  backgroundColor: Colors.white24,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Límite ${nexaMoneyLabel(credit.creditLimit, credit.currency)}',
              style: theme.textTheme.bodySmall?.copyWith(color: supporting),
            ),
          ] else
            Text(
              credit == null
                  ? message
                  : credit.active
                  ? 'Línea activa'
                  : 'Línea inactiva',
              style: theme.textTheme.bodySmall?.copyWith(color: supporting),
            ),
        ],
      ),
    );
  }
}

class BuyerRequestWidget extends StatelessWidget {
  const BuyerRequestWidget({
    super.key,
    this.code,
    this.status,
    this.productCount,
    this.refreshing = false,
    required this.message,
    required this.onOpen,
  });
  final String? code;
  final String? status;
  final int? productCount;
  final bool refreshing;
  final String message;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return _HomeSurface(
      color: NexaBuyerPalette.attentionSurface,
      onTap: onOpen,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.edit_note,
                color: NexaBuyerPalette.attention,
                size: 22,
              ),
              const Spacer(),
              const Icon(
                Icons.chevron_right,
                color: NexaBuyerPalette.attention,
                size: 18,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            refreshing ? 'Solicitud · Actualizando…' : 'Última solicitud',
            style: theme.textTheme.labelMedium?.copyWith(
              color: NexaBuyerPalette.attention,
            ),
          ),
          const SizedBox(height: 4),
          if (productCount != null)
            RichText(
              textScaler: MediaQuery.textScalerOf(context),
              text: TextSpan(
                style: theme.textTheme.bodyMedium,
                children: [
                  TextSpan(
                    text: '$productCount',
                    style: theme.textTheme.headlineLarge,
                  ),
                  TextSpan(
                    text: productCount == 1 ? ' producto' : ' productos',
                  ),
                ],
              ),
            ),
          const SizedBox(height: 8),
          if (code != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                code!,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: NexaBuyerPalette.attention,
                ),
              ),
            ),
          const SizedBox(height: 4),
          Text(
            status == null ? message : nexaStatusLabel(status!),
            style: theme.textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

class BuyerSecondaryWidgets extends StatelessWidget {
  const BuyerSecondaryWidgets({super.key, required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      if (children.length < 2 ||
          constraints.maxWidth < 320 ||
          MediaQuery.textScalerOf(context).scale(16) / 16 >= 1.3) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0) const SizedBox(height: 12),
              children[i],
            ],
          ],
        );
      }
      return IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0) const SizedBox(width: 12),
              Expanded(child: children[i]),
            ],
          ],
        ),
      );
    },
  );
}

class _HomeSurface extends StatelessWidget {
  const _HomeSurface({
    required this.child,
    required this.onTap,
    this.color = NexaBuyerPalette.cardSurface,
  });
  final Widget child;
  final VoidCallback onTap;
  final Color color;

  @override
  Widget build(BuildContext context) => NexaActionSurface(
    color: color,
    onTap: onTap,
    child: Padding(padding: const EdgeInsets.all(16), child: child),
  );
}
