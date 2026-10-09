import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'buyer_access_view_model.dart';

final class SignInPage extends StatefulWidget {
  const SignInPage({super.key});

  @override
  State<SignInPage> createState() => _SignInPageState();
}

final class _SignInPageState extends State<SignInPage> {
  final _formKey = GlobalKey<FormState>();
  final _identifier = TextEditingController();
  final _password = TextEditingController();

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    await context.read<BuyerAccessViewModel>().signIn(
      _identifier.text,
      _password.text,
    );
    _password.clear();
  }

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: ListView(
              padding: const EdgeInsets.all(24),
              shrinkWrap: true,
              children: [
                Text(
                  'Nexa Buyer',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 8),
                Text(
                  'Inicia sesión para consultar el catálogo y tus pedidos.',
                  style: Theme.of(context).textTheme.bodyLarge,
                ),
                const SizedBox(height: 24),
                Form(
                  key: _formKey,
                  child: Column(
                    children: [
                      TextFormField(
                        controller: _identifier,
                        autofillHints: const [
                          AutofillHints.username,
                          AutofillHints.email,
                        ],
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: 'Correo o usuario',
                          border: OutlineInputBorder(),
                        ),
                        validator: (value) =>
                            value == null || value.trim().isEmpty
                            ? 'Escribe tu correo o usuario.'
                            : null,
                      ),
                      const SizedBox(height: 16),
                      TextFormField(
                        controller: _password,
                        obscureText: true,
                        autofillHints: const [AutofillHints.password],
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => _submit(),
                        decoration: const InputDecoration(
                          labelText: 'Contraseña',
                          border: OutlineInputBorder(),
                        ),
                        validator: (value) => value == null || value.isEmpty
                            ? 'Escribe tu contraseña.'
                            : null,
                      ),
                    ],
                  ),
                ),
                if (access.message case final message?) ...[
                  const SizedBox(height: 16),
                  _InlineMessage(message: message, isError: true),
                ],
                const SizedBox(height: 20),
                FilledButton(
                  onPressed: access.busy ? null : _submit,
                  child: access.busy
                      ? const SizedBox.square(
                          dimension: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Continuar'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

final class _InlineMessage extends StatelessWidget {
  const _InlineMessage({required this.message, this.isError = false});

  final String message;
  final bool isError;

  @override
  Widget build(BuildContext context) => Text(
    message,
    style: TextStyle(
      color: isError ? Theme.of(context).colorScheme.error : null,
    ),
  );
}
