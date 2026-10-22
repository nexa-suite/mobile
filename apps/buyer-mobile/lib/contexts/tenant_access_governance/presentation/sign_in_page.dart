import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import 'buyer_password_recovery_view_model.dart';
import 'buyer_access_view_model.dart';

const _nexaBlue = Color(0xFF2563EB);
const _nexaInk = Color(0xFF111827);
const _nexaMuted = Color(0xFF52627A);
const _nexaBorder = Color(0xFFE4EBF5);

final class SignInPage extends StatefulWidget {
  const SignInPage({
    super.key,
    this.startInRecovery = false,
    this.initialEmail = '',
    this.passwordResetToken,
  });

  final bool startInRecovery;
  final String initialEmail;
  final String? passwordResetToken;

  @override
  State<SignInPage> createState() => _SignInPageState();
}

final class _SignInPageState extends State<SignInPage> {
  final _formKey = GlobalKey<FormState>();
  final _recoveryFormKey = GlobalKey<FormState>();
  final _resetPasswordFormKey = GlobalKey<FormState>();
  final _identifier = TextEditingController();
  final _password = TextEditingController();
  final _recoveryEmail = TextEditingController();
  final _newPassword = TextEditingController();
  final _confirmPassword = TextEditingController();
  bool _obscurePassword = true;
  bool _obscureNewPassword = true;
  bool _obscureConfirmation = true;
  bool _showCredentials = false;

  @override
  void initState() {
    super.initState();
    _showCredentials =
        widget.startInRecovery || widget.passwordResetToken != null;
    _recoveryEmail.text = widget.initialEmail;
  }

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    _recoveryEmail.dispose();
    _newPassword.dispose();
    _confirmPassword.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (context.read<BuyerAccessViewModel>().busy ||
        !_formKey.currentState!.validate()) {
      return;
    }
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
      backgroundColor: const Color(0xFFF8FAFF),
      resizeToAvoidBottomInset: true,
      body: SafeArea(
        child: _NexaAccessBackdrop(
          child: LayoutBuilder(
            builder: (context, constraints) {
              if (constraints.maxWidth >= 840) {
                return _wideLayout(context, access);
              }
              return _phoneLayout(context, access, constraints.maxHeight);
            },
          ),
        ),
      ),
    );
  }

  Widget _phoneLayout(
    BuildContext context,
    BuyerAccessViewModel access,
    double availableHeight,
  ) {
    final compact = availableHeight < 720;
    if (widget.passwordResetToken != null) {
      return Consumer<BuyerPasswordRecoveryViewModel>(
        builder: (context, recovery, child) =>
            _passwordResetPhone(context, access, recovery, availableHeight),
      );
    }
    if (widget.startInRecovery) {
      return Consumer<BuyerPasswordRecoveryViewModel>(
        builder: (context, recovery, child) =>
            _passwordRecoveryPhone(context, access, recovery, availableHeight),
      );
    }
    if (_showCredentials) {
      return _credentialsPhone(context, access, availableHeight);
    }

    final minimumHeight = availableHeight > 48
        ? availableHeight - 48
        : availableHeight;
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: minimumHeight),
        child: IntrinsicHeight(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: _brandWordmark(width: 142),
                  ),
                  SizedBox(height: compact ? 24 : 56),
                  Text(
                    'NEXA · CLIENTES',
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                      color: _nexaBlue,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1.5,
                    ),
                  ),
                  const SizedBox(height: 14),
                  Semantics(
                    header: true,
                    child: Text(
                      'Tus compras, en un solo lugar.',
                      style: Theme.of(context).textTheme.displayLarge?.copyWith(
                        fontSize: 36,
                        height: 1.08,
                        letterSpacing: -0.9,
                      ),
                    ),
                  ),
                  const SizedBox(height: 18),
                  Text(
                    'Explora el catálogo, prepara tus solicitudes y sigue tus '
                    'pedidos desde un espacio sencillo.',
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                      color: _nexaMuted,
                      fontSize: 16,
                      height: 1.6,
                    ),
                  ),
                  SizedBox(height: compact ? 18 : 30),
                  const _BuyerJourney(),
                  const Spacer(),
                  SizedBox(height: compact ? 18 : 32),
                  SizedBox(
                    height: 58,
                    child: FilledButton(
                      onPressed: () => setState(() => _showCredentials = true),
                      style: _primaryButtonStyle,
                      child: const Text('Continuar'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _credentialsPhone(
    BuildContext context,
    BuyerAccessViewModel access,
    double availableHeight,
  ) {
    return _centeredPhonePage(
      context,
      availableHeight: availableHeight,
      onBack: access.busy
          ? null
          : () => setState(() => _showCredentials = false),
      content: _credentialsCard(context, access),
    );
  }

  Widget _passwordRecoveryPhone(
    BuildContext context,
    BuyerAccessViewModel access,
    BuyerPasswordRecoveryViewModel recovery,
    double availableHeight,
  ) => _centeredPhonePage(
    context,
    availableHeight: availableHeight,
    onBack: () => context.go('/sign-in'),
    content: _passwordRecoveryCard(context, recovery),
  );

  Widget _passwordResetPhone(
    BuildContext context,
    BuyerAccessViewModel access,
    BuyerPasswordRecoveryViewModel recovery,
    double availableHeight,
  ) => _centeredPhonePage(
    context,
    availableHeight: availableHeight,
    onBack: () => context.go('/sign-in'),
    content: _passwordResetCard(context, access, recovery),
  );

  Widget _centeredPhonePage(
    BuildContext context, {
    required double availableHeight,
    required VoidCallback? onBack,
    required Widget content,
  }) {
    final minimumHeight = availableHeight > 48
        ? availableHeight - 48
        : availableHeight;
    return SingleChildScrollView(
      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 24),
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: minimumHeight),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 480),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    IconButton(
                      tooltip: 'Volver',
                      onPressed: onBack,
                      icon: const Icon(Icons.arrow_back_rounded),
                    ),
                    const SizedBox(width: 6),
                    _brandWordmark(width: 126),
                  ],
                ),
                const SizedBox(height: 28),
                content,
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _wideLayout(BuildContext context, BuyerAccessViewModel access) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Expanded(
          flex: 5,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(56, 48, 48, 48),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _brandWordmark(width: 154),
                const Spacer(),
                Text(
                  'NEXA · CLIENTES',
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: _nexaBlue,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.5,
                  ),
                ),
                const SizedBox(height: 16),
                Semantics(
                  header: true,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 540),
                    child: Text(
                      'Tus compras, en un solo lugar.',
                      style: theme.textTheme.displayLarge?.copyWith(
                        fontSize: 48,
                        height: 1.08,
                        letterSpacing: -1.4,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 510),
                  child: Text(
                    'Explora el catálogo, prepara tus solicitudes y consulta '
                    'tus pedidos desde un solo lugar.',
                    style: theme.textTheme.bodyLarge?.copyWith(
                      color: _nexaMuted,
                      fontSize: 17,
                      height: 1.65,
                    ),
                  ),
                ),
                const SizedBox(height: 34),
                const _BuyerJourney(),
                const Spacer(),
              ],
            ),
          ),
        ),
        Expanded(
          flex: 5,
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(36),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: _wideAuthCard(context, access),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _wideAuthCard(BuildContext context, BuyerAccessViewModel access) {
    if (widget.passwordResetToken != null || widget.startInRecovery) {
      return Consumer<BuyerPasswordRecoveryViewModel>(
        builder: (context, recovery, child) => widget.passwordResetToken != null
            ? _passwordResetCard(context, access, recovery)
            : _passwordRecoveryCard(context, recovery),
      );
    }
    return _credentialsCard(context, access);
  }

  Widget _brandWordmark({required double width}) => Semantics(
    label: 'Nexa',
    image: true,
    child: ExcludeSemantics(
      child: Image.asset(
        'assets/brand/nexa-wordmark.png',
        width: width,
        fit: BoxFit.contain,
      ),
    ),
  );

  Widget _credentialsCard(BuildContext context, BuyerAccessViewModel access) {
    final theme = Theme.of(context);
    final inputDecoration = _authInputDecoration();

    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: _nexaBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Inicia sesión en tu cuenta',
              style: theme.textTheme.headlineLarge?.copyWith(
                fontSize: 28,
                height: 1.2,
                letterSpacing: -0.5,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Ingresa el correo y la contraseña de tu acceso de comprador.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: _nexaMuted,
                height: 1.55,
              ),
            ),
            const SizedBox(height: 24),
            AutofillGroup(
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      controller: _identifier,
                      enabled: !access.busy,
                      style: theme.textTheme.bodyLarge,
                      autofillHints: const [
                        AutofillHints.username,
                        AutofillHints.email,
                      ],
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.next,
                      decoration: inputDecoration.copyWith(
                        labelText: 'Correo electrónico',
                        hintText: 'nombre@empresa.com',
                        prefixIcon: const Icon(Icons.mail_outline_rounded),
                      ),
                      validator: (value) =>
                          value == null || value.trim().isEmpty
                          ? 'Escribe tu correo electrónico.'
                          : null,
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      controller: _password,
                      enabled: !access.busy,
                      style: theme.textTheme.bodyLarge,
                      obscureText: _obscurePassword,
                      autofillHints: const [AutofillHints.password],
                      textInputAction: TextInputAction.done,
                      onFieldSubmitted: (_) => _submit(),
                      decoration: inputDecoration.copyWith(
                        labelText: 'Contraseña',
                        hintText: 'Tu contraseña',
                        prefixIcon: const Icon(Icons.lock_outline_rounded),
                        suffixIcon: IconButton(
                          tooltip: _obscurePassword
                              ? 'Mostrar contraseña'
                              : 'Ocultar contraseña',
                          onPressed: access.busy
                              ? null
                              : () => setState(
                                  () => _obscurePassword = !_obscurePassword,
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
            const SizedBox(height: 24),
            SizedBox(
              height: 56,
              child: FilledButton(
                onPressed: access.busy ? null : _submit,
                style: _primaryButtonStyle,
                child: access.busy
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Iniciar sesión'),
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 56,
              child: OutlinedButton.icon(
                onPressed: access.busy
                    ? null
                    : () => _handleBiometricLogin(context, access),
                icon: const Icon(Icons.fingerprint_rounded, size: 23),
                label: const Text('Continuar con huella o rostro'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: _nexaInk,
                  side: const BorderSide(color: _nexaBorder),
                  textStyle: const TextStyle(fontWeight: FontWeight.w600),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),
            if (!access.biometricStatus.canSignIn)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  'Inicia sesión y activa esta opción en Mi cuenta.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(color: _nexaMuted),
                ),
              ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: access.busy
                    ? null
                    : () => context.push(
                        '/forgot-password',
                        extra: _identifier.text.trim(),
                      ),
                child: const Text('¿Olvidaste tu contraseña?'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _handleBiometricLogin(
    BuildContext context,
    BuyerAccessViewModel access,
  ) async {
    if (!access.biometricStatus.canSignIn) {
      await access.refreshBiometricLoginStatus();
      if (!context.mounted) return;
    }
    if (access.biometricStatus.canSignIn) {
      await access.signInWithBiometrics();
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        icon: const Icon(Icons.fingerprint_rounded, color: _nexaBlue),
        title: const Text('Activa tu acceso biométrico'),
        content: const Text(
          'Inicia sesión con tu contraseña y activa huella o rostro desde '
          'Mi cuenta. Después podrás entrar desde aquí.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Entendido'),
          ),
        ],
      ),
    );
  }

  Future<void> _requestPasswordReset(
    BuyerPasswordRecoveryViewModel recovery,
  ) async {
    if (recovery.busy || !_recoveryFormKey.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    await recovery.requestReset(_recoveryEmail.text);
  }

  Future<void> _submitNewPassword(
    BuildContext context,
    BuyerAccessViewModel access,
    BuyerPasswordRecoveryViewModel recovery,
  ) async {
    final token = widget.passwordResetToken;
    if (token == null ||
        token.isEmpty ||
        recovery.busy ||
        !_resetPasswordFormKey.currentState!.validate()) {
      return;
    }
    FocusScope.of(context).unfocus();
    await recovery.resetPassword(token: token, newPassword: _newPassword.text);
    if (!recovery.passwordReset || !context.mounted) return;
    await access.signOut();
  }

  Widget _passwordRecoveryCard(
    BuildContext context,
    BuyerPasswordRecoveryViewModel recovery,
  ) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: _nexaBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: recovery.requestSent
            ? Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(
                    Icons.mark_email_read_outlined,
                    size: 44,
                    color: _nexaBlue,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Revisa tu correo',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.headlineLarge?.copyWith(
                      fontSize: 26,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    'Si existe una cuenta asociada a ese correo, recibirás un '
                    'enlace para cambiar tu contraseña. Revisa también la '
                    'carpeta de correo no deseado.',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: _nexaMuted,
                      height: 1.55,
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    _recoveryEmail.text.trim(),
                    textAlign: TextAlign.center,
                    style: theme.textTheme.titleSmall,
                  ),
                  const SizedBox(height: 20),
                  OutlinedButton(
                    onPressed: () {
                      recovery.resetRequestState();
                      setState(() {});
                    },
                    child: const Text('Usar otro correo'),
                  ),
                ],
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    'Recupera tu acceso',
                    style: theme.textTheme.headlineLarge?.copyWith(
                      fontSize: 28,
                      height: 1.2,
                      letterSpacing: -0.5,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Escribe el correo de tu cuenta Buyer. Te enviaremos un '
                    'enlace para crear una contraseña nueva.',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: _nexaMuted,
                      height: 1.55,
                    ),
                  ),
                  const SizedBox(height: 24),
                  Form(
                    key: _recoveryFormKey,
                    child: TextFormField(
                      controller: _recoveryEmail,
                      enabled: !recovery.busy,
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.done,
                      autofillHints: const [AutofillHints.email],
                      onFieldSubmitted: (_) => _requestPasswordReset(recovery),
                      decoration: _authInputDecoration().copyWith(
                        labelText: 'Correo electrónico',
                        hintText: 'nombre@empresa.com',
                        prefixIcon: const Icon(Icons.mail_outline_rounded),
                      ),
                      validator: (value) {
                        final email = value?.trim() ?? '';
                        if (email.isEmpty) {
                          return 'Escribe tu correo electrónico.';
                        }
                        if (!email.contains('@')) {
                          return 'Revisa el formato de tu correo electrónico.';
                        }
                        return null;
                      },
                    ),
                  ),
                  if (recovery.message case final message?) ...[
                    const SizedBox(height: 16),
                    NexaNotice(message, isError: true),
                  ],
                  const SizedBox(height: 24),
                  SizedBox(
                    height: 56,
                    child: FilledButton(
                      onPressed: recovery.busy
                          ? null
                          : () => _requestPasswordReset(recovery),
                      style: _primaryButtonStyle,
                      child: recovery.busy
                          ? const SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('Enviar enlace'),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _passwordResetCard(
    BuildContext context,
    BuyerAccessViewModel access,
    BuyerPasswordRecoveryViewModel recovery,
  ) {
    final theme = Theme.of(context);
    final token = widget.passwordResetToken;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: Colors.white,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: _nexaBorder),
      ),
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: token == null || token.isEmpty
            ? _passwordResetMessage(
                context,
                title: 'Enlace no disponible',
                message:
                    'Este enlace no es válido o ya venció. Solicita uno nuevo.',
                icon: Icons.link_off_rounded,
                action: () => context.go('/sign-in'),
                actionLabel: 'Ir a iniciar sesión',
              )
            : recovery.passwordReset
            ? _passwordResetMessage(
                context,
                title: 'Contraseña actualizada',
                message: 'Ya puedes entrar con tu nueva contraseña.',
                icon: Icons.check_circle_outline_rounded,
                action: () => context.go('/sign-in'),
                actionLabel: 'Ir a iniciar sesión',
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    'Crea una contraseña nueva',
                    style: theme.textTheme.headlineLarge?.copyWith(
                      fontSize: 28,
                      height: 1.2,
                      letterSpacing: -0.5,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Usa entre 12 y 128 caracteres. Evita contraseñas fáciles '
                    'de adivinar.',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: _nexaMuted,
                      height: 1.55,
                    ),
                  ),
                  const SizedBox(height: 24),
                  Form(
                    key: _resetPasswordFormKey,
                    child: Column(
                      children: [
                        TextFormField(
                          controller: _newPassword,
                          enabled: !recovery.busy,
                          obscureText: _obscureNewPassword,
                          autofillHints: const [AutofillHints.newPassword],
                          textInputAction: TextInputAction.next,
                          decoration: _authInputDecoration().copyWith(
                            labelText: 'Nueva contraseña',
                            prefixIcon: const Icon(Icons.lock_outline_rounded),
                            suffixIcon: IconButton(
                              tooltip: _obscureNewPassword
                                  ? 'Mostrar contraseña'
                                  : 'Ocultar contraseña',
                              onPressed: recovery.busy
                                  ? null
                                  : () => setState(
                                      () => _obscureNewPassword =
                                          !_obscureNewPassword,
                                    ),
                              icon: Icon(
                                _obscureNewPassword
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                              ),
                            ),
                          ),
                          validator: (value) {
                            final password = value ?? '';
                            if (password.length < 12 || password.length > 128) {
                              return 'Usa entre 12 y 128 caracteres.';
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 16),
                        TextFormField(
                          controller: _confirmPassword,
                          enabled: !recovery.busy,
                          obscureText: _obscureConfirmation,
                          autofillHints: const [AutofillHints.newPassword],
                          textInputAction: TextInputAction.done,
                          onFieldSubmitted: (_) =>
                              _submitNewPassword(context, access, recovery),
                          decoration: _authInputDecoration().copyWith(
                            labelText: 'Repite la contraseña',
                            prefixIcon: const Icon(Icons.lock_outline_rounded),
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
                          validator: (value) => value != _newPassword.text
                              ? 'Las contraseñas no coinciden.'
                              : null,
                        ),
                      ],
                    ),
                  ),
                  if (recovery.message case final message?) ...[
                    const SizedBox(height: 16),
                    NexaNotice(message, isError: true),
                  ],
                  const SizedBox(height: 24),
                  SizedBox(
                    height: 56,
                    child: FilledButton(
                      onPressed: recovery.busy
                          ? null
                          : () => _submitNewPassword(context, access, recovery),
                      style: _primaryButtonStyle,
                      child: recovery.busy
                          ? const SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Text('Guardar contraseña'),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _passwordResetMessage(
    BuildContext context, {
    required String title,
    required String message,
    required IconData icon,
    required VoidCallback action,
    required String actionLabel,
  }) {
    final theme = Theme.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Icon(icon, size: 44, color: _nexaBlue),
        const SizedBox(height: 16),
        Text(
          title,
          textAlign: TextAlign.center,
          style: theme.textTheme.headlineLarge?.copyWith(fontSize: 26),
        ),
        const SizedBox(height: 12),
        Text(
          message,
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium?.copyWith(
            color: _nexaMuted,
            height: 1.55,
          ),
        ),
        const SizedBox(height: 24),
        SizedBox(
          height: 56,
          child: FilledButton(
            onPressed: action,
            style: _primaryButtonStyle,
            child: Text(actionLabel),
          ),
        ),
      ],
    );
  }

  InputDecoration _authInputDecoration() => InputDecoration(
    filled: true,
    fillColor: Colors.white,
    contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: const BorderSide(color: Color(0xFFD9E4F2)),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: const BorderSide(color: Color(0xFFD9E4F2)),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: const BorderSide(color: _nexaBlue, width: 2),
    ),
  );
}

ButtonStyle get _primaryButtonStyle => FilledButton.styleFrom(
  backgroundColor: _nexaBlue,
  foregroundColor: Colors.white,
  textStyle: const TextStyle(fontWeight: FontWeight.w600),
  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
);

final class _NexaAccessBackdrop extends StatelessWidget {
  const _NexaAccessBackdrop({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFF4F8FF), Color(0xFFFBFDFF), Color(0xFFF7FAFF)],
        stops: [0, 0.52, 1],
      ),
    ),
    child: Stack(
      fit: StackFit.expand,
      children: [
        Positioned(
          top: -150,
          left: -130,
          child: Container(
            width: 390,
            height: 360,
            decoration: const BoxDecoration(
              gradient: RadialGradient(
                colors: [Color(0x59BFDBFE), Color(0x00BFDBFE)],
              ),
            ),
          ),
        ),
        Positioned(
          right: -160,
          bottom: -170,
          child: Container(
            width: 430,
            height: 390,
            decoration: const BoxDecoration(
              gradient: RadialGradient(
                colors: [Color(0x45DBEAFE), Color(0x00DBEAFE)],
              ),
            ),
          ),
        ),
        child,
      ],
    ),
  );
}

final class _BuyerJourney extends StatelessWidget {
  const _BuyerJourney();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    const steps = [
      ('01', 'Catálogo'),
      ('02', 'Solicitudes'),
      ('03', 'Entregas'),
    ];
    return Semantics(
      label: 'Catálogo, solicitudes y entregas',
      child: ExcludeSemantics(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final (number, label) in steps)
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(right: 14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(height: 2, color: const Color(0xFFBFDBFE)),
                      const SizedBox(height: 10),
                      Text(
                        number,
                        style: theme.textTheme.labelLarge?.copyWith(
                          color: _nexaBlue,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        label,
                        style: theme.textTheme.titleSmall?.copyWith(
                          color: _nexaInk,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
