import 'package:flutter/material.dart';

import '../../../core/presentation/nexa_wordmark.dart';

/// Access composition shares the Operations rhythm, with a lighter buying surface.
final class BuyerAccessLayout extends StatelessWidget {
  const BuyerAccessLayout({
    super.key,
    required this.signingIn,
    required this.content,
    this.onBack,
  });

  final bool signingIn;
  final Widget content;
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFE5F0FF), Color(0xFFFBFDFF), Color(0xFFF1F7FF)],
        stops: [0, .52, 1],
      ),
    ),
    child: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final keyboard = MediaQuery.viewInsetsOf(context).bottom > 0;
          final wide =
              constraints.maxWidth >= 840 &&
              constraints.maxHeight >= 600 &&
              MediaQuery.textScalerOf(context).scale(16) < 24 &&
              !keyboard;
          final header = Row(
            children: [
              if (signingIn)
                IconButton(
                  tooltip: 'Volver a bienvenida',
                  onPressed: onBack,
                  icon: const Icon(Icons.arrow_back_rounded),
                ),
              const NexaWordmark(),
            ],
          );
          final form = ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: content,
          );
          if (wide) {
            return Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1120),
                child: Padding(
                  padding: const EdgeInsets.all(40),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            header,
                            const SizedBox(height: 48),
                            Text(
                              'Tus compras,\nmás sencillas.',
                              style: Theme.of(context).textTheme.headlineLarge,
                            ),
                            const SizedBox(height: 16),
                            const Text(
                              'Encuentra tus productos y acompaña cada pedido hasta recibirlo.',
                            ),
                            const SizedBox(height: 32),
                            BuyerAccessSteps(signingIn: signingIn),
                          ],
                        ),
                      ),
                      const SizedBox(width: 48),
                      Expanded(child: SingleChildScrollView(child: form)),
                    ],
                  ),
                ),
              ),
            );
          }
          return CustomScrollView(
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            slivers: [
              SliverFillRemaining(
                hasScrollBody: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(24, 16, 24, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (!keyboard) ...[
                        header,
                        const SizedBox(height: 24),
                        BuyerAccessSteps(signingIn: signingIn),
                      ],
                      if (!keyboard) const Spacer(flex: 2),
                      SizedBox(height: keyboard ? 8 : 32),
                      Center(child: form),
                      if (!keyboard) const Spacer(),
                    ],
                  ),
                ),
              ),
            ],
          );
        },
      ),
    ),
  );
}

final class BuyerAccessSteps extends StatelessWidget {
  const BuyerAccessSteps({super.key, this.signingIn = false});
  final bool signingIn;
  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 24,
    runSpacing: 8,
    children: [
      Text(
        '01 Bienvenida',
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
          color: !signingIn
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
      Text(
        '02 Acceso',
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
          color: signingIn
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ],
  );
}
