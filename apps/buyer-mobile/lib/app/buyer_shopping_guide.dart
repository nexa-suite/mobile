import 'package:flutter/material.dart';

import '../core/presentation/nexa_buyer_palette.dart';

/// A short, expandable guide to the Buyer request and order flow.
final class BuyerShoppingGuide extends StatefulWidget {
  const BuyerShoppingGuide({super.key});

  @override
  State<BuyerShoppingGuide> createState() => _BuyerShoppingGuideState();
}

final class _BuyerShoppingGuideState extends State<BuyerShoppingGuide> {
  bool _expanded = false;

  void _toggle() => setState(() => _expanded = !_expanded);

  @override
  Widget build(BuildContext context) {
    final disableAnimations = MediaQuery.disableAnimationsOf(context);
    final theme = Theme.of(context);
    final guideContent = _expanded
        ? Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: LayoutBuilder(
              builder: (context, constraints) {
                final wide =
                    constraints.hasBoundedWidth && constraints.maxWidth >= 640;
                final steps = _steps();
                if (!wide) {
                  return Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final step in steps)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: step,
                        ),
                    ],
                  );
                }
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (var index = 0; index < steps.length; index++) ...[
                      if (index > 0) const SizedBox(width: 12),
                      Expanded(child: steps[index]),
                    ],
                  ],
                );
              },
            ),
          )
        : const SizedBox.shrink();

    return Material(
      color: NexaBuyerPalette.cardSurface,
      borderRadius: BorderRadius.circular(16),
      clipBehavior: Clip.antiAlias,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Semantics(
            container: true,
            button: true,
            expanded: _expanded,
            label: 'Cómo hacer tu pedido',
            onTap: _toggle,
            onTapHint: _expanded ? 'Ocultar los pasos' : 'Mostrar los pasos',
            child: ExcludeSemantics(
              child: InkWell(
                onTap: _toggle,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(minHeight: 56),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 12,
                    ),
                    child: Row(
                      children: [
                        const _GuideIcon(
                          icon: Icons.help_outline,
                          color: NexaBuyerPalette.primary,
                          surface: NexaBuyerPalette.primarySurface,
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Text(
                            'Cómo hacer tu pedido',
                            style: theme.textTheme.titleMedium,
                          ),
                        ),
                        const SizedBox(width: 8),
                        if (disableAnimations)
                          Icon(
                            _expanded
                                ? Icons.keyboard_arrow_up
                                : Icons.keyboard_arrow_down,
                            color: NexaBuyerPalette.muted,
                          )
                        else
                          AnimatedRotation(
                            turns: _expanded ? .5 : 0,
                            duration: const Duration(milliseconds: 160),
                            curve: Curves.easeInOut,
                            child: const Icon(
                              Icons.keyboard_arrow_down,
                              color: NexaBuyerPalette.muted,
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
          ClipRect(
            child: disableAnimations
                ? guideContent
                : AnimatedSize(
                    duration: const Duration(milliseconds: 240),
                    curve: Curves.easeInOut,
                    alignment: Alignment.topCenter,
                    child: guideContent,
                  ),
          ),
        ],
      ),
    );
  }

  List<Widget> _steps() => [
    _GuideStep(
      title: '1. Elige tus productos',
      description: 'Busca productos y revisa su presentación.',
      icon: Icons.search,
      color: NexaBuyerPalette.primary,
      surface: NexaBuyerPalette.primarySurface,
    ),
    _GuideStep(
      title: '2. Revisa tu solicitud',
      description: 'Envíala para revisión.',
      icon: Icons.fact_check_outlined,
      color: NexaBuyerPalette.attention,
      surface: NexaBuyerPalette.attentionSurface,
    ),
    _GuideStep(
      title: '3. Sigue tu pedido',
      description: 'Consulta su estado y entrega.',
      icon: Icons.local_shipping_outlined,
      color: NexaBuyerPalette.positive,
      surface: NexaBuyerPalette.positiveSurface,
    ),
  ];
}

final class _GuideStep extends StatelessWidget {
  const _GuideStep({
    required this.title,
    required this.description,
    required this.icon,
    required this.color,
    required this.surface,
  });

  final String title;
  final String description;
  final IconData icon;
  final Color color;
  final Color surface;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact =
            constraints.hasBoundedWidth && constraints.maxWidth >= 280;
        if (compact) {
          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _GuideIcon(icon: icon, color: color, surface: surface),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: theme.textTheme.titleSmall),
                    const SizedBox(height: 8),
                    Text(description, style: theme.textTheme.bodySmall),
                  ],
                ),
              ),
            ],
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _GuideIcon(icon: icon, color: color, surface: surface),
            const SizedBox(height: 8),
            Text(title, style: theme.textTheme.titleSmall),
            const SizedBox(height: 8),
            Text(description, style: theme.textTheme.bodySmall),
          ],
        );
      },
    );
  }
}

final class _GuideIcon extends StatelessWidget {
  const _GuideIcon({
    required this.icon,
    required this.color,
    required this.surface,
  });

  final IconData icon;
  final Color color;
  final Color surface;

  @override
  Widget build(BuildContext context) => Container(
    width: 40,
    height: 40,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      color: surface,
      borderRadius: BorderRadius.circular(10),
    ),
    child: Icon(icon, color: color, size: 20),
  );
}
