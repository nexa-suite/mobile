import 'package:flutter/material.dart';

import '../application/buyer_access_repository.dart';

final class BuyerPasswordRecoveryDialog extends StatefulWidget {
  const BuyerPasswordRecoveryDialog({
    super.key,
    required this.initialEmail,
    required this.requestReset,
  });

  final String initialEmail;
  final Future<BuyerPasswordResetRequestResult> Function(String email)
  requestReset;

  @override
  State<BuyerPasswordRecoveryDialog> createState() =>
      BuyerPasswordRecoveryDialogState();
}

final class BuyerPasswordRecoveryDialogState
    extends State<BuyerPasswordRecoveryDialog> {
  late final TextEditingController _email;
  bool _sending = false;
  bool _submitted = false;
  String? _fieldError;
  bool _requestFailed = false;
  String? _notice;

  @override
  void initState() {
    super.initState();
    _email = TextEditingController(text: widget.initialEmail.trim());
  }

  @override
  void dispose() {
    _email.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final email = _email.text.trim();
    if (!RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(email)) {
      setState(() {
        _fieldError = 'Ingresa un correo válido.';
        _requestFailed = false;
        _notice = null;
      });
      return;
    }
    setState(() {
      _sending = true;
      _submitted = false;
      _fieldError = null;
      _requestFailed = false;
      _notice = null;
    });
    final result = await widget.requestReset(email);
    if (!mounted) return;
    setState(() {
      _sending = false;
      _submitted = result == BuyerPasswordResetRequestResult.requested;
      _notice = switch (result) {
        BuyerPasswordResetRequestResult.requested => 'Si la cuenta existe, enviaremos instrucciones para recuperar el acceso.',
        BuyerPasswordResetRequestResult.rateLimited =>
          'Hubo demasiadas solicitudes. Inténtalo más tarde.',
        BuyerPasswordResetRequestResult.unavailable =>
          'No pudimos enviar las instrucciones. Inténtalo otra vez.',
      };
      _requestFailed = result != BuyerPasswordResetRequestResult.requested;
    });
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    scrollable: true,
    title: const Text('Recupera tu cuenta'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          'Ingresa tu correo. Si pertenece a una cuenta, enviaremos instrucciones.',
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _email,
          enabled: !_sending && !_submitted,
          autofocus: true,
          keyboardType: TextInputType.emailAddress,
          textInputAction: TextInputAction.done,
          autofillHints: const [AutofillHints.email],
          onSubmitted: (_) => _sending || _submitted ? null : _submit(),
          decoration: InputDecoration(
            labelText: 'Correo de trabajo',
            errorText: _fieldError,
          ),
        ),
        if (_notice case final notice?) ...[
          const SizedBox(height: 12),
          Semantics(
            liveRegion: true,
            child: Text(
              notice,
              style: TextStyle(
                color: _requestFailed
                    ? Theme.of(context).colorScheme.error
                    : Theme.of(context).colorScheme.primary,
              ),
            ),
          ),
        ],
      ],
    ),
    actions: [
      TextButton(
        onPressed: _sending ? null : () => Navigator.pop(context),
        child: Text(_submitted ? 'Listo' : 'Cancelar'),
      ),
      if (!_submitted)
        FilledButton(
          onPressed: _sending ? null : _submit,
          child: _sending
              ? const SizedBox.square(
                  dimension: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Enviar instrucciones'),
        ),
    ],
  );
}
