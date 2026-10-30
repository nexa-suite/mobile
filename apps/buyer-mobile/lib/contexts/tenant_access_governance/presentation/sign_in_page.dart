import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import 'buyer_access_layout.dart';
import 'buyer_access_view_model.dart';
import 'buyer_password_recovery_dialog.dart';

final class SignInPage extends StatefulWidget {
  const SignInPage({
    super.key,
    this.startInRecovery = false,
    this.initialEmail = '',
  });

  final bool startInRecovery;
  final String initialEmail;

  @override
  State<SignInPage> createState() => _SignInPageState();
}

final class _SignInPageState extends State<SignInPage> {
  final _formKey = GlobalKey<FormState>();
  final _identifier = TextEditingController();
  final _password = TextEditingController();
  bool _showSignIn = false;
  bool _obscurePassword = true;

  @override
  void initState() {
    super.initState();
    _showSignIn = widget.startInRecovery;
    _identifier.text = widget.initialEmail;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      context.read<BuyerAccessViewModel>().refreshBiometricAvailability();
      if (widget.startInRecovery) {
        unawaited(_openPasswordRecovery(initialEmail: widget.initialEmail));
      }
    });
  }

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    super.dispose();
  }

  void _returnToWelcome() {
    FocusScope.of(context).unfocus();
    _password.clear();
    setState(() => _showSignIn = false);
  }

  Future<void> _submit() async {
    final access = context.read<BuyerAccessViewModel>();
    if (access.busy ||
        access.biometricBusy ||
        !(_formKey.currentState?.validate() ?? false)) {
      return;
    }
    FocusScope.of(context).unfocus();
    try {
      await access.signIn(_identifier.text, _password.text);
    } finally {
      if (mounted) _password.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    final busy = access.busy || access.biometricBusy;
    return PopScope<Object?>(
      canPop: !_showSignIn && !busy,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && _showSignIn && !busy) _returnToWelcome();
      },
      child: Scaffold(
        body: BuyerAccessLayout(
          signingIn: _showSignIn,
          onBack: busy ? null : _returnToWelcome,
          content: _showSignIn
              ? _credentials(context, access)
              : _welcome(context),
        ),
      ),
    );
  }

  Widget _welcome(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    mainAxisSize: MainAxisSize.min,
    children: [
      ExcludeSemantics(
        child: Align(
          alignment: Alignment.centerLeft,
          child: Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primaryContainer,
              borderRadius: BorderRadius.circular(24),
            ),
            child: Icon(
              Icons.shopping_bag_outlined,
              size: 40,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
        ),
      ),
      const SizedBox(height: 24),
      Text(
        'Todo para tu próxima compra.',
        style: Theme.of(context).textTheme.headlineLarge,
      ),
      const SizedBox(height: 16),
      Text(
        'Explora tus productos, prepara una solicitud y sigue tus entregas desde un solo lugar.',
        style: Theme.of(context).textTheme.bodyLarge,
      ),
      const SizedBox(height: 32),
      FilledButton(
        onPressed: () => setState(() => _showSignIn = true),
        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(56)),
        child: const Text('Continuar'),
      ),
    ],
  );

  Widget _credentials(BuildContext context, BuyerAccessViewModel access) {
    final theme = Theme.of(context);
    final busy = access.busy || access.biometricBusy;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        border: Border.all(color: const Color(0xFFE0EAF8)),
        borderRadius: BorderRadius.circular(24),
      ),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Semantics(
              header: true,
              child: Text(
                'Inicia sesión',
                style: theme.textTheme.headlineMedium,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Usa el acceso de comprador que te compartió tu empresa.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 24),
            AutofillGroup(
              child: Form(
                key: _formKey,
                child: Column(
                  children: [
                    TextFormField(
                      controller: _identifier,
                      enabled: !busy,
                      autofillHints: const [
                        AutofillHints.username,
                        AutofillHints.email,
                      ],
                      autocorrect: false,
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.next,
                      decoration: _field(
                        'Correo o usuario',
                        Icons.mail_outline_rounded,
                      ),
                      validator: (value) =>
                          value == null || value.trim().isEmpty
                          ? 'Escribe tu correo o usuario.'
                          : null,
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      controller: _password,
                      enabled: !busy,
                      obscureText: _obscurePassword,
                      autocorrect: false,
                      enableSuggestions: false,
                      autofillHints: const [AutofillHints.password],
                      textInputAction: TextInputAction.done,
                      onFieldSubmitted: (_) => _submit(),
                      decoration:
                          _field(
                            'Contraseña',
                            Icons.lock_outline_rounded,
                          ).copyWith(
                            suffixIcon: IconButton(
                              tooltip: _obscurePassword
                                  ? 'Mostrar contraseña'
                                  : 'Ocultar contraseña',
                              onPressed: busy
                                  ? null
                                  : () => setState(
                                      () =>
                                          _obscurePassword = !_obscurePassword,
                                    ),
                              icon: Icon(
                                _obscurePassword
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                              ),
                            ),
                          ),
                      validator: (value) => value == null || value.isEmpty
                          ? 'Escribe tu contraseña.'
                          : null,
                    ),
                  ],
                ),
              ),
            ),
            if (access.message case final message?) ...[
              const SizedBox(height: 16),
              NexaNotice(message, isError: true),
            ],
            if (access.biometricMessage case final message?) ...[
              const SizedBox(height: 16),
              NexaNotice(message, isError: true),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: busy ? null : _submit,
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(56),
              ),
              child: access.busy
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Iniciar sesión'),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: busy || !access.biometricLoginAvailable
                  ? null
                  : access.signInWithBiometrics,
              style: OutlinedButton.styleFrom(
                minimumSize: const Size.fromHeight(52),
                foregroundColor: theme.colorScheme.primary,
                side: BorderSide(color: theme.colorScheme.outlineVariant),
              ),
              icon: access.biometricBusy
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.fingerprint_rounded, size: 28),
              label: const Text('Usar huella o Face ID'),
            ),
            if (!access.biometricLoginAvailable)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  access.biometricSupported
                      ? 'Actívala en Perfil después de iniciar sesión.'
                      : 'Configura biometría en tu dispositivo.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: busy ? null : _openPasswordRecovery,
              style: TextButton.styleFrom(
                minimumSize: const Size.fromHeight(48),
              ),
              child: const Text('¿Olvidaste tu contraseña?'),
            ),
          ],
        ),
      ),
    );
  }

  InputDecoration _field(String hint, IconData icon) => InputDecoration(
    hintText: hint,
    prefixIcon: Icon(icon),
    filled: true,
    fillColor: Colors.white,
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
  );

  Future<void> _openPasswordRecovery({String? initialEmail}) =>
      showDialog<void>(
        context: context,
        builder: (context) => BuyerPasswordRecoveryDialog(
          initialEmail:
              initialEmail ??
              (_identifier.text.contains('@') ? _identifier.text : ''),
          requestReset: context
              .read<BuyerAccessViewModel>()
              .requestPasswordReset,
        ),
      );
}
