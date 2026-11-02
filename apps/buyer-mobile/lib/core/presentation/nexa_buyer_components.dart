import 'package:flutter/material.dart';

import 'nexa_buyer_theme.dart';

enum NexaSkeletonShape { rectangle, rounded, circle }

/// A static loading placeholder with one caller-localized screen-reader label.
///
/// Its shape is decorative; the child is excluded from semantics so assistive
/// technology announces only [semanticLabel]. With no [width], rectangles and
/// rounded shapes fill a width-bounded parent and use [height] as a finite
/// fallback in an unbounded layout. Circles use [height] as their diameter.
final class NexaSkeleton extends StatelessWidget {
  const NexaSkeleton({
    super.key,
    this.width,
    required this.height,
    required this.semanticLabel,
    this.shape = NexaSkeletonShape.rounded,
  });

  final double? width;
  final double height;
  final String semanticLabel;
  final NexaSkeletonShape shape;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tokens =
        theme.extension<NexaThemeExtension>() ?? NexaBuyerTheme.tokens;
    final isCircle = shape == NexaSkeletonShape.circle;
    return LayoutBuilder(
      builder: (context, constraints) {
        final effectiveWidth =
            width ??
            (isCircle || !constraints.hasBoundedWidth
                ? height
                : constraints.maxWidth);
        final radius = switch (shape) {
          NexaSkeletonShape.rectangle => 0.0,
          NexaSkeletonShape.rounded => tokens.radiusControl,
          NexaSkeletonShape.circle => 0.0,
        };

        return Semantics(
          container: true,
          liveRegion: true,
          label: semanticLabel,
          child: ExcludeSemantics(
            child: SizedBox(
              width: effectiveWidth,
              height: height,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: theme.colorScheme.surfaceContainerHighest,
                  shape: isCircle ? BoxShape.circle : BoxShape.rectangle,
                  borderRadius: isCircle ? null : BorderRadius.circular(radius),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Keeps reading width and side gutters consistent on phones and tablets.
final class NexaPageWidth extends StatelessWidget {
  const NexaPageWidth({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.symmetric(horizontal: 16),
    this.maxWidth = 760,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final double maxWidth;

  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: Padding(padding: padding, child: child),
    ),
  );
}

final class NexaPageIntro extends StatelessWidget {
  const NexaPageIntro({
    super.key,
    required this.title,
    required this.description,
    this.icon,
  });

  final String title;
  final String description;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (icon != null) ...[
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: theme.colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: theme.colorScheme.primary),
            ),
            const SizedBox(width: 16),
          ],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: theme.textTheme.headlineMedium),
                const SizedBox(height: 4),
                Text(
                  description,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

final class NexaFactTile extends StatelessWidget {
  const NexaFactTile({
    super.key,
    required this.label,
    required this.value,
    this.icon,
    this.emphasis = false,
  });
  final String label;
  final String value;
  final IconData? icon;
  final bool emphasis;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 20, color: theme.colorScheme.primary),
            const SizedBox(width: 12),
          ],
          Expanded(
            child: Text(
              label,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: emphasis
                  ? theme.textTheme.titleMedium
                  : theme.textTheme.bodyMedium,
            ),
          ),
        ],
      ),
    );
  }
}

/// Shared presentation primitives; business state is supplied by each context.
final class NexaSectionHeader extends StatelessWidget {
  const NexaSectionHeader({
    super.key,
    required this.title,
    required this.description,
    this.eyebrow,
    this.action,
  });
  final String title;
  final String description;
  final String? eyebrow;
  final Widget? action;

  @override
  Widget build(BuildContext context) => NexaPageWidth(
    padding: EdgeInsets.zero,
    child: Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Card.outlined(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      title,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  ?action,
                ],
              ),
              const SizedBox(height: 4),
              Text(description, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      ),
    ),
  );
}

final class NexaStatusBadge extends StatelessWidget {
  const NexaStatusBadge(this.status, {super.key, this.label});
  final String status;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final code = status
        .replaceAllMapped(
          RegExp(r'([a-z])([A-Z])'),
          (match) => '${match[1]}_${match[2]}',
        )
        .toUpperCase();
    final theme = Theme.of(context);
    final tokens =
        theme.extension<NexaThemeExtension>() ?? NexaBuyerTheme.tokens;
    final negative = {
      'REJECTED',
      'CANCELLED',
      'WITHDRAWN',
      'EXPIRED',
      'FAILED',
      'UNAVAILABLE',
      'INACTIVE',
    }.contains(code);
    final pending = {
      'DRAFT',
      'SUBMITTED',
      'PENDING',
      'PENDING_PAYMENT',
      'CHANGES_PROPOSED',
      'LOW',
      'PREPARING',
      'AWAITING_PAYMENT',
      'PROCESSING',
    }.contains(code);
    final positive = {
      'AVAILABLE',
      'ACTIVE',
      'APPROVED',
      'CONFIRMED',
      'SUCCEEDED',
      'FULFILLED',
      'READY_FOR_DISPATCH',
      'DELIVERED',
      'COMPLETED',
      'SEALED',
      'ACCEPTED',
    }.contains(code);
    final foreground = negative
        ? tokens.dangerText
        : pending
        ? tokens.warningText
        : positive
        ? tokens.success
        : theme.colorScheme.onPrimaryContainer;
    final background = negative
        ? tokens.dangerContainer
        : pending
        ? tokens.warningContainer
        : positive
        ? tokens.successContainer
        : theme.colorScheme.primaryContainer;
    return Semantics(
      label: label ?? nexaStatusLabel(status),
      excludeSemantics: true,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadius.circular(tokens.radiusChip),
          border: Border.all(color: foreground.withValues(alpha: .15)),
        ),
        child: Text(
          label ?? nexaStatusLabel(status),
          style: Theme.of(context).textTheme.labelSmall
              ?.copyWith(color: foreground, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}

String nexaStatusLabel(String value) => switch (value.toUpperCase()) {
  'DRAFT' => 'Borrador',
  'PRODUCTS_COMPLETE' => 'Productos definidos',
  'DESTINATION_COMPLETE' => 'Destino definido',
  'ROUTE_VALIDATED' => 'Ruta validada',
  'COMMERCIAL_REVIEW_COMPLETE' => 'Condiciones revisadas',
  'READY_TO_SUBMIT' => 'Lista para enviar',
  'SUBMITTED' => 'En revisión',
  'CHANGES_PROPOSED' => 'Cambios por revisar',
  'CONVERTED' || 'CONVERTED_TO_ORDER' => 'Convertida en pedido',
  'CONFIRMED' => 'Confirmado',
  'APPROVED' => 'Aprobado',
  'PENDING' => 'Pendiente',
  'PENDING_PAYMENT' || 'AWAITING_PAYMENT' => 'Pago pendiente',
  'PROCESSING' => 'Pendiente de revisión',
  'SUCCEEDED' => 'Confirmado',
  'PREPARING' || 'IN_PREPARATION' || 'PICKING' => 'En preparación',
  'IN_FULFILLMENT' => 'En preparación',
  'PARTIALLY_FULFILLED' => 'Preparación parcial',
  'FULFILLED' => 'Preparado',
  'READY_FOR_DISPATCH' => 'Listo para despacho',
  'DISPATCHED' => 'Despachado',
  'IN_TRANSIT' => 'En camino',
  'DELIVERED' => 'Entregado',
  'PARTIALLY_DELIVERED' => 'Entrega parcial',
  'COMPLETED' => 'Completado',
  'SEALED' => 'Constancia confirmada',
  'ACCEPTED' => 'Aceptado',
  'REJECTED' => 'Rechazado',
  'CANCELLED' => 'Cancelado',
  'WITHDRAWN' => 'Retirada',
  'EXPIRED' => 'Vencida',
  'FAILED' => 'No completado',
  'ACTIVE' => 'Activa',
  'INACTIVE' => 'Inactiva',
  'AVAILABLE' => 'Disponible',
  'LOW' => 'Disponibilidad baja',
  'UNAVAILABLE' => 'No disponible',
  _ => value.replaceAll('_', ' '),
};

final class NexaEmptyState extends StatelessWidget {
  const NexaEmptyState({
    super.key,
    required this.title,
    this.description,
    this.icon = Icons.inbox_outlined,
    this.actionLabel,
    this.onAction,
  });
  final String title;
  final String? description;
  final IconData icon;
  final String? actionLabel;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) => Center(
    child: SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(24),
              ),
              child: Icon(
                icon,
                size: 32,
                color: Theme.of(context).colorScheme.primary,
              ),
            ),
            const SizedBox(height: 20),
            Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            if (description != null) ...[
              const SizedBox(height: 8),
              Text(description!, textAlign: TextAlign.center),
            ],
            if (onAction != null && actionLabel != null) ...[
              const SizedBox(height: 20),
              OutlinedButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    ),
  );
}

final class NexaNotice extends StatelessWidget {
  const NexaNotice(this.message, {super.key, this.isError = false});
  final String message;
  final bool isError;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isError
            ? Theme.of(context).colorScheme.errorContainer
            : Theme.of(context).colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            isError ? Icons.error_outline : Icons.info_outline,
            size: 20,
            color: isError
                ? Theme.of(context).colorScheme.onErrorContainer
                : Theme.of(context).colorScheme.onPrimaryContainer,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                color: isError
                    ? Theme.of(context).colorScheme.onErrorContainer
                    : Theme.of(context).colorScheme.onPrimaryContainer,
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

final class NexaRecordCard extends StatelessWidget {
  const NexaRecordCard({
    super.key,
    required this.title,
    required this.status,
    required this.icon,
    this.reference = false,
    this.subtitle,
    this.footer,
    this.onTap,
  });
  final String title;
  final bool reference;
  final String status;
  final IconData icon;
  final String? subtitle;
  final Widget? footer;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Icon(
                    icon,
                    size: 20,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Container(
                      padding: reference
                          ? const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 6,
                            )
                          : EdgeInsets.zero,
                      decoration: reference
                          ? BoxDecoration(
                              color: Theme.of(context)
                                  .colorScheme
                                  .surfaceContainer,
                              borderRadius: BorderRadius.circular(6),
                            )
                          : null,
                      child: Text(
                        title,
                        style: reference
                            ? Theme.of(context).textTheme.labelMedium
                            : Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                  ),
                ),
                if (onTap != null) const Icon(Icons.chevron_right),
              ],
            ),
            const SizedBox(height: 10),
            NexaStatusBadge(status),
            if (subtitle != null) ...[
              const SizedBox(height: 8),
              Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
            ],
            if (footer != null) ...[
              const SizedBox(height: 8),
              const Divider(),
              const SizedBox(height: 8),
              footer!,
            ],
          ],
        ),
      ),
    ),
  );
}

final class NexaProductVisual extends StatelessWidget {
  const NexaProductVisual({
    super.key,
    required this.name,
    this.coldChain,
    this.height = 128,
  });
  final String name;
  final String? coldChain;
  final double height;
  @override
  Widget build(BuildContext context) => Semantics(
    label: name,
    image: true,
    child: Container(
      height: height,
      width: double.infinity,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [
            Theme.of(context).colorScheme.secondary,
            Theme.of(context).colorScheme.primary,
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Stack(
        children: [
          Positioned(
            right: -24,
            top: -40,
            child: Container(
              width: 180,
              height: 180,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: Colors.white.withValues(alpha: .08),
                  width: 24,
                ),
              ),
            ),
          ),
          Center(
            child: Icon(
              coldChain == null || coldChain == 'NONE'
                  ? Icons.inventory_2_outlined
                  : Icons.ac_unit,
              size: 52,
              color: Theme.of(context).colorScheme.tertiary,
            ),
          ),
          if (coldChain != null && coldChain != 'NONE')
            Positioned(
              left: 16,
              bottom: 12,
              child: Text(
                coldChain!,
                style: const TextStyle(color: Colors.white, fontSize: 12),
              ),
            ),
        ],
      ),
    ),
  );
}
