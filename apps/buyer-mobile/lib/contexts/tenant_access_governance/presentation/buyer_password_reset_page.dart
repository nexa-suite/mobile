import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'buyer_access_view_model.dart';
import 'buyer_password_recovery_view_model.dart';

final class BuyerPasswordResetPage extends StatefulWidget {
  const BuyerPasswordResetPage({super.key, required this.token});

  final String token;

  @override
  State<BuyerPasswordResetPage> createState() => _BuyerPasswordResetPageState();
}

final class _BuyerPasswordResetPageState extends State<BuyerPasswordResetPage> {
  final _formKey = GlobalKey<FormState>();
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  bool _obscurePassword = true;
  bool _obscureConfirmation = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        context.read<BuyerPasswordRecoveryViewModel>().resetRequestState();
      }
    });
  }

  @override
  void dispose() {
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _submit(BuyerPasswordRecoveryViewModel recovery) async {
    if (recovery.busy || !_formKey.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    await recovery.resetPassword(
      token: widget.token,
      newPassword: _password.text,
    );
    if (!mounted || !recovery.passwordReset) return;
    await context.read<BuyerAccessViewModel>().signOut();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Consumer<BuyerPasswordRecoveryViewModel>(
              builder: (context, recovery, child) {
                final theme = Theme.of(context);
                final invalidToken = widget.token.trim().isEmpty;
                if (invalidToken) {
                  return _message(
                    context,
                    title: 'Enlace no disponible',
                    message: 'Este enlace no es válido o ya venció. Solicita uno nuevo.',
                    actionLabel: 'Solicitar otro enlace',
                    onAction: () => context.go('/forgot-password'),
                  );
                }
                if (recovery.passwordReset) {
                  return _message(
                    context,
                    title: 'Contraseña actualizada',
                    message: 'Ya puedes entrar con tu nueva contraseña.',
                    actionLabel: 'Ir a iniciar sesión',
                    onAction: () => context.go('/sign-in'),
                  );
                }
                return Card(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Form(
                      key: _formKey,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            'Crea una contraseña nueva',
                            style: theme.textTheme.headlineSmall,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            'Usa entre 12 y 128 caracteres.',
                            style: theme.textTheme.bodyMedium,
                          ),
                          const SizedBox(height: 24),
                          TextFormField(
                            controller: _password,
                            enabled: !recovery.busy,
                            obscureText: _obscurePassword,
                            autofillHints: const [AutofillHints.newPassword],
                            textInputAction: TextInputAction.next,
                            decoration: InputDecoration(
                              labelText: 'Nueva contraseña',
                              suffixIcon: IconButton(
                                tooltip: _obscurePassword
                                    ? 'Mostrar contraseña'
                                    : 'Ocultar contraseña',
                                onPressed: recovery.busy
                                    ? null
                                    : () => setState(
                                        () => _obscurePassword =
                                            !_obscurePassword,
                                      ),
                                icon: Icon(
                                  _obscurePassword
                                      ? Icons.visibility_outlined
                                      : Icons.visibility_off_outlined,
                                ),
                              ),
                            ),
                            validator: (value) {
                              final password = value ?? '';
                              if (password.length < 12 ||
                                  password.length > 128) {
                                return 'Usa entre 12 y 128 caracteres.';
                              }
                              return null;
                            },
                          ),
                          const SizedBox(height: 16),
                          TextFormField(
                            controller: _confirmation,
                            enabled: !recovery.busy,
                            obscureText: _obscureConfirmation,
                            autofillHints: const [AutofillHints.newPassword],
                            textInputAction: TextInputAction.done,
                            onFieldSubmitted: (_) => _submit(recovery),
                            decoration: InputDecoration(
                              labelText: 'Repite la contraseña',
                              suffixIcon: IconButton(
                                tooltip: _obscureConfirmation
                                    ? 'Mostrar contraseña'
                                    : 'Ocultar contraseña',
                                onPressed: recovery.busy
                                    ? null
                                    : () => setState(
                                        () => _obscureConfirmation =
                                            !_obscureConfirmation,
                                      ),
                                icon: Icon(
                                  _obscureConfirmation
                                      ? Icons.visibility_outlined
                                      : Icons.visibility_off_outlined,
                                ),
                              ),
                            ),
                            validator: (value) => value != _password.text
                                ? 'Las contraseñas no coinciden.'
                                : null,
                          ),
                          if (recovery.message case final message?) ...[
                            const SizedBox(height: 16),
                            Text(
                              message,
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: theme.colorScheme.error,
                              ),
                            ),
                          ],
                          const SizedBox(height: 24),
                          FilledButton(
                            onPressed: recovery.busy
                                ? null
                                : () => _submit(recovery),
                            child: recovery.busy
                                ? const SizedBox.square(
                                    dimension: 20,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                    ),
                                  )
                                : const Text('Guardar contraseña'),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ),
    ),
  );

  Widget _message(
    BuildContext context, {
    required String title,
    required String message,
    required String actionLabel,
    required VoidCallback onAction,
  }) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: theme.textTheme.headlineSmall),
            const SizedBox(height: 12),
            Text(message, style: theme.textTheme.bodyMedium),
            const SizedBox(height: 24),
            FilledButton(onPressed: onAction, child: Text(actionLabel)),
          ],
        ),
      ),
    );
  }
}
