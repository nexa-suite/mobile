import 'package:flutter/material.dart';

import '../../../l10n/generated/app_localizations.dart';

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
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context)!;
    return DecoratedBox(
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
                    tooltip: strings.backToWelcome,
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
                                strings.wideWelcomeTitle,
                                style: Theme.of(context)
                                    .textTheme
                                    .headlineLarge,
                              ),
                              const SizedBox(height: 16),
                              Text(strings.wideWelcomeDescription),
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
            return SingleChildScrollView(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: const EdgeInsets.fromLTRB(24, 16, 24, 16),
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  minHeight: (constraints.maxHeight - 32).clamp(
                    0.0,
                    double.infinity,
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    if (!keyboard)
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          header,
                          const SizedBox(height: 24),
                          BuyerAccessSteps(signingIn: signingIn),
                        ],
                      ),
                    Padding(
                      padding: EdgeInsets.only(top: keyboard ? 8 : 32),
                      child: Center(child: form),
                    ),
                    if (!keyboard) const SizedBox.shrink(),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

final class BuyerAccessSteps extends StatelessWidget {
  const BuyerAccessSteps({super.key, this.signingIn = false});
  final bool signingIn;
  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context)!;
    return Wrap(
      spacing: 24,
      runSpacing: 8,
      children: [
        Text(
          strings.welcomeStep,
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
            color: !signingIn
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        Text(
          strings.signInStep,
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
            color: signingIn
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}
