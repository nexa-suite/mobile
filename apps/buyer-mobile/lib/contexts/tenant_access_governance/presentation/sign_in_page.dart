import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/buyer_access_repository.dart';
import 'buyer_access_view_model.dart';

const _nexaWarm = Color(0xFFE4A174);
const _nexaGreen = Color(0xFF6F9F85);

final class SignInPage extends StatefulWidget {
  const SignInPage({super.key});

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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        context.read<BuyerAccessViewModel>().refreshBiometricAvailability();
      }
    });
  }

  @override
  void dispose() {
    _identifier.dispose();
    _password.dispose();
    super.dispose();
  }

  void _openSignIn() {
    FocusScope.of(context).unfocus();
    setState(() => _showSignIn = true);
  }

  void _returnToWelcome() {
    FocusScope.of(context).unfocus();
    setState(() => _showSignIn = false);
  }

  Future<void> _submit() async {
    final access = context.read<BuyerAccessViewModel>();
    if (access.busy || !_formKey.currentState!.validate()) return;

    FocusScope.of(context).unfocus();
    await access.signIn(_identifier.text, _password.text);
    _password.clear();
  }

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    return PopScope<Object?>(
      canPop: !_showSignIn && !access.busy,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && _showSignIn && !access.busy) _returnToWelcome();
      },
      child: Scaffold(
        body: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final isWide = constraints.maxWidth >= 760;
              final contentWidth = math.min(
                constraints.maxWidth,
                isWide ? 1120.0 : 480.0,
              );
              return Center(
                child: SizedBox(
                  width: contentWidth,
                  height: constraints.maxHeight,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
                    child: _showSignIn
                        ? _buildSignIn(context, access, isWide)
                        : _buildWelcome(isWide),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildWelcome(bool isWide) => LayoutBuilder(
    builder: (context, constraints) {
      final artPanel = Card(
        margin: EdgeInsets.zero,
        elevation: 1,
        shadowColor: const Color(0x140F172A),
        clipBehavior: Clip.antiAlias,
        child: const _BuyerWelcomeIllustration(),
      );

      if (isWide) {
        final panelHeight = math.min(constraints.maxHeight, 540.0);
        final copyHeight = math.min(panelHeight, 420.0);
        return Center(
          child: SizedBox(
            height: panelHeight,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(flex: 11, child: artPanel),
                const SizedBox(width: 16),
                Expanded(
                  flex: 10,
                  child: SizedBox(
                    height: copyHeight,
                    child: _welcomeCopyCard(
                      context,
                      fillHeight: true,
                      centerContent: true,
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      }

      if (constraints.maxHeight < 640) {
        return SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SizedBox(height: 210, child: artPanel),
              const SizedBox(height: 12),
              _welcomeCopyCard(context, fillHeight: false),
            ],
          ),
        );
      }

      final artHeight = math.min(360.0, constraints.maxHeight * 0.44);
      final copyHeight = math.min(330.0, constraints.maxHeight * 0.41);
      return Center(
        child: SizedBox(
          height: artHeight + 12 + copyHeight,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SizedBox(height: artHeight, child: artPanel),
              const SizedBox(height: 12),
              SizedBox(
                height: copyHeight,
                child: _welcomeCopyCard(context, fillHeight: true),
              ),
            ],
          ),
        ),
      );
    },
  );

  Widget _welcomeCopyCard(
    BuildContext context, {
    required bool fillHeight,
    bool centerContent = false,
  }) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 1,
      shadowColor: const Color(0x140F172A),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        child: Column(
          mainAxisSize: fillHeight ? MainAxisSize.max : MainAxisSize.min,
          mainAxisAlignment: centerContent
              ? MainAxisAlignment.center
              : MainAxisAlignment.start,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Image.asset(
                  'assets/brand/nexa-mobile.png',
                  width: 48,
                  height: 48,
                  fit: BoxFit.contain,
                  semanticLabel: 'Nexa mark',
                ),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerLow,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    'BUYER',
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.primary,
                      letterSpacing: 1.1,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Text(
              'Tu espacio de compra',
              style: theme.textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Explora el catálogo, prepara solicitudes y consulta tus pedidos desde un solo lugar.',
              style: theme.textTheme.bodyLarge?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                height: 1.5,
              ),
            ),
            if (centerContent)
              const SizedBox(height: 24)
            else if (fillHeight)
              const Spacer()
            else
              const SizedBox(height: 24),
            FilledButton(
              onPressed: _openSignIn,
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(56),
              ),
              child: const Text('Continuar'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSignIn(
    BuildContext context,
    BuyerAccessViewModel access,
    bool isWide,
  ) => Column(
    children: [
      if (isWide)
        Card(
          margin: EdgeInsets.zero,
          elevation: 1,
          shadowColor: const Color(0x140F172A),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            child: Row(
              children: [
                IconButton(
                  tooltip: 'Volver a bienvenida',
                  onPressed: access.busy ? null : _returnToWelcome,
                  constraints: const BoxConstraints(
                    minWidth: 48,
                    minHeight: 48,
                  ),
                  icon: const Icon(Icons.arrow_back_rounded),
                ),
                const SizedBox(width: 4),
                Image.asset(
                  'assets/brand/nexa-mobile.png',
                  width: 76,
                  height: 44,
                  fit: BoxFit.contain,
                  semanticLabel: 'Nexa logo',
                ),
                const Spacer(),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Text(
                    'BUYER',
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                      letterSpacing: 1.1,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      if (isWide) const SizedBox(height: 12),
      Expanded(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final formContent = isWide
                ? Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Expanded(
                        child: _buyerSignInContextCard(
                          context,
                          fillHeight: false,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: _credentialsCard(
                          context,
                          access,
                          fillHeight: false,
                        ),
                      ),
                    ],
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(
                        height: math.min(170.0, constraints.maxHeight * 0.22),
                        child: Card(
                          margin: EdgeInsets.zero,
                          elevation: 1,
                          shadowColor: const Color(0x140F172A),
                          clipBehavior: Clip.antiAlias,
                          child: const _BuyerWelcomeIllustration(),
                        ),
                      ),
                      const SizedBox(height: 12),
                      _credentialsCard(
                        context,
                        access,
                        fillHeight: false,
                        showMobileHeader: true,
                      ),
                    ],
                  );

            return SingleChildScrollView(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: constraints.maxHeight),
                child: Center(child: formContent),
              ),
            );
          },
        ),
      ),
    ],
  );

  Widget _credentialsCard(
    BuildContext context,
    BuyerAccessViewModel access, {
    required bool fillHeight,
    bool showMobileHeader = false,
  }) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 1,
      shadowColor: const Color(0x140F172A),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 18),
        child: Column(
          mainAxisSize: fillHeight ? MainAxisSize.max : MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (showMobileHeader) ...[
              Row(
                children: [
                  IconButton(
                    tooltip: 'Volver a bienvenida',
                    onPressed: access.busy ? null : _returnToWelcome,
                    constraints: const BoxConstraints(
                      minWidth: 48,
                      minHeight: 48,
                    ),
                    icon: const Icon(Icons.arrow_back_rounded),
                  ),
                  const SizedBox(width: 4),
                  Image.asset(
                    'assets/brand/nexa-mobile.png',
                    width: 44,
                    height: 44,
                    fit: BoxFit.contain,
                    semanticLabel: 'Nexa mark',
                  ),
                  const Spacer(),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Text(
                      'BUYER',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.primary,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
            ],
            Text(
              'Iniciar sesión',
              style: theme.textTheme.headlineMedium?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Ingresa con el correo o usuario de tu cuenta.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 24),
            AutofillGroup(
              child: Form(
                key: _formKey,
                child: Column(
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          'Correo o usuario',
                          style: theme.textTheme.labelLarge?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextFormField(
                          controller: _identifier,
                          enabled: !access.busy,
                          autofillHints: const [
                            AutofillHints.username,
                            AutofillHints.email,
                          ],
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
                          decoration: InputDecoration(
                            hintText: 'nombre@empresa.com',
                            prefixIcon: const Icon(
                              Icons.alternate_email_rounded,
                            ),
                            filled: true,
                            fillColor: theme.colorScheme.surfaceContainerLow,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 18,
                            ),
                          ),
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                              ? 'Escribe tu correo o usuario.'
                              : null,
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          'Contraseña',
                          style: theme.textTheme.labelLarge?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                        const SizedBox(height: 6),
                        TextFormField(
                          controller: _password,
                          enabled: !access.busy,
                          obscureText: _obscurePassword,
                          autofillHints: const [AutofillHints.password],
                          textInputAction: TextInputAction.done,
                          onFieldSubmitted: (_) => _submit(),
                          decoration: InputDecoration(
                            hintText: 'Escribe tu contraseña',
                            prefixIcon: const Icon(Icons.lock_outline_rounded),
                            filled: true,
                            fillColor: theme.colorScheme.surfaceContainerLow,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 18,
                            ),
                            suffixIcon: IconButton(
                              tooltip: _obscurePassword
                                  ? 'Mostrar contraseña'
                                  : 'Ocultar contraseña',
                              constraints: const BoxConstraints(
                                minWidth: 48,
                                minHeight: 48,
                              ),
                              onPressed: access.busy
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
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton(
                        onPressed: access.busy ? null : _openPasswordRecovery,
                        child: const Text('¿Olvidaste tu contraseña?'),
                      ),
                    ),
                    OutlinedButton.icon(
                      onPressed:
                          access.busy ||
                              access.biometricBusy ||
                              !access.biometricLoginAvailable
                          ? null
                          : access.signInWithBiometrics,
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(52),
                      ),
                      icon: access.biometricBusy
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.fingerprint),
                      label: const Text('Usar huella o Face ID'),
                    ),
                    if (!access.biometricLoginAvailable) ...[
                      const SizedBox(height: 6),
                      Text(
                        access.biometricSupported
                            ? 'Actívala en Perfil después de iniciar sesión.'
                            : 'Configura biometría en tu dispositivo.',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            if (access.message case final message?) ...[
              const SizedBox(height: 16),
              NexaNotice(message, isError: true),
            ],
            if (access.biometricMessage case final message?) ...[
              const SizedBox(height: 12),
              NexaNotice(message, isError: true),
            ],
            if (fillHeight) const Spacer() else const SizedBox(height: 22),
            FilledButton(
              onPressed: access.busy ? null : _submit,
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
          ],
        ),
      ),
    );
  }

  Future<void> _openPasswordRecovery() => showDialog<void>(
    context: context,
    builder: (context) => _BuyerPasswordRecoveryDialog(
      initialEmail: _identifier.text.contains('@') ? _identifier.text : '',
      requestReset: context.read<BuyerAccessViewModel>().requestPasswordReset,
    ),
  );

  Widget _buyerSignInContextCard(
    BuildContext context, {
    required bool fillHeight,
  }) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 1,
      shadowColor: const Color(0x140F172A),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          mainAxisSize: fillHeight ? MainAxisSize.max : MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 190, child: _BuyerWelcomeIllustration()),
            const SizedBox(height: 20),
            Text(
              'Bienvenido de nuevo',
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Revisa tu catálogo, solicitudes y entregas de proveedores.',
              style: theme.textTheme.bodyLarge?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                height: 1.5,
              ),
            ),
            if (fillHeight) const Spacer(),
          ],
        ),
      ),
    );
  }
}

final class _BuyerPasswordRecoveryDialog extends StatefulWidget {
  const _BuyerPasswordRecoveryDialog({
    required this.initialEmail,
    required this.requestReset,
  });

  final String initialEmail;
  final Future<BuyerPasswordResetRequestResult> Function(String email)
  requestReset;

  @override
  State<_BuyerPasswordRecoveryDialog> createState() =>
      _BuyerPasswordRecoveryDialogState();
}

final class _BuyerPasswordRecoveryDialogState
    extends State<_BuyerPasswordRecoveryDialog> {
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

final class _BuyerWelcomeIllustration extends StatelessWidget {
  const _BuyerWelcomeIllustration();

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return ClipRRect(
      borderRadius: BorderRadius.circular(22),
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              colorScheme.surfaceContainerLow,
              colorScheme.primaryContainer,
            ],
          ),
        ),
        child: Semantics(
          image: true,
          label: 'Ilustración de una persona revisando productos en su laptop',
          child: ExcludeSemantics(
            child: CustomPaint(
              painter: _BuyerWelcomePainter(
                primary: colorScheme.primary,
                primaryContainer: colorScheme.primaryContainer,
                secondary: colorScheme.secondary,
                outline: colorScheme.outline,
                surface: colorScheme.surface,
              ),
              child: const SizedBox.expand(),
            ),
          ),
        ),
      ),
    );
  }
}

final class _BuyerWelcomePainter extends CustomPainter {
  _BuyerWelcomePainter({
    required this.primary,
    required this.primaryContainer,
    required this.secondary,
    required this.outline,
    required this.surface,
  });

  final Color primary;
  final Color primaryContainer;
  final Color secondary;
  final Color outline;
  final Color surface;

  @override
  void paint(Canvas canvas, Size size) {
    const artSize = Size(360, 228);
    final scale = math.min(
      size.width / artSize.width,
      size.height / artSize.height,
    );
    canvas.save();
    canvas.translate(
      (size.width - artSize.width * scale) / 2,
      (size.height - artSize.height * scale) / 2,
    );
    canvas.scale(scale);

    final softCircle = Paint()..color = Colors.white.withValues(alpha: 0.72);
    canvas.drawCircle(const Offset(67, 57), 30, softCircle);
    canvas.drawCircle(
      const Offset(303, 48),
      34,
      Paint()..color = _nexaGreen.withValues(alpha: 0.16),
    );
    canvas.drawOval(
      const Rect.fromLTWH(70, 184, 226, 16),
      Paint()..color = secondary.withValues(alpha: 0.1),
    );

    final linePaint = Paint()
      ..color = outline
      ..strokeWidth = 4
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final chair = Path()
      ..moveTo(117, 95)
      ..lineTo(117, 148)
      ..lineTo(176, 148)
      ..moveTo(117, 127)
      ..lineTo(102, 171)
      ..moveTo(174, 148)
      ..lineTo(187, 173);
    canvas.drawPath(chair, linePaint);

    final shirt = Paint()..color = primary;
    final torso = Path()
      ..moveTo(128, 79)
      ..quadraticBezierTo(141, 70, 158, 80)
      ..lineTo(173, 125)
      ..quadraticBezierTo(152, 139, 126, 128)
      ..lineTo(119, 98)
      ..close();
    canvas.drawPath(torso, shirt);

    final skin = Paint()..color = _nexaWarm;
    canvas.drawCircle(const Offset(145, 57), 17, skin);
    final hair = Path()
      ..moveTo(128, 55)
      ..quadraticBezierTo(127, 35, 145, 37)
      ..quadraticBezierTo(160, 38, 161, 52)
      ..quadraticBezierTo(151, 48, 146, 45)
      ..quadraticBezierTo(139, 54, 128, 55)
      ..close();
    canvas.drawPath(hair, Paint()..color = secondary);
    canvas.drawCircle(const Offset(151, 57), 1.8, Paint()..color = secondary);

    final armPaint = Paint()
      ..color = skin.color
      ..strokeWidth = 9
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final reachingArm = Path()
      ..moveTo(159, 89)
      ..quadraticBezierTo(181, 99, 196, 111);
    canvas.drawPath(reachingArm, armPaint);
    canvas.drawCircle(const Offset(198, 112), 5, skin);

    final trousers = Paint()..color = secondary;
    final seatedLeg = Path()
      ..moveTo(131, 126)
      ..quadraticBezierTo(125, 146, 139, 157)
      ..lineTo(177, 165)
      ..lineTo(184, 157)
      ..lineTo(154, 138)
      ..lineTo(160, 128)
      ..close();
    canvas.drawPath(seatedLeg, trousers);
    final lowerLeg = Path()
      ..moveTo(142, 153)
      ..lineTo(130, 174)
      ..lineTo(111, 178)
      ..lineTo(109, 184)
      ..lineTo(146, 184)
      ..lineTo(159, 160)
      ..close();
    canvas.drawPath(lowerLeg, trousers);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(103, 179, 48, 8),
        const Radius.circular(4),
      ),
      trousers,
    );

    final laptopBorder = Paint()
      ..color = primary
      ..strokeWidth = 4
      ..style = PaintingStyle.stroke;
    final laptop = RRect.fromRectAndRadius(
      const Rect.fromLTWH(196, 84, 80, 62),
      const Radius.circular(7),
    );
    canvas.drawRRect(laptop, laptopBorder);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(204, 92, 64, 46),
        const Radius.circular(3),
      ),
      Paint()..color = surface,
    );
    final laptopBase = Path()
      ..moveTo(190, 148)
      ..lineTo(282, 148)
      ..lineTo(274, 156)
      ..lineTo(198, 156)
      ..close();
    canvas.drawPath(laptopBase, Paint()..color = secondary);
    canvas.drawLine(
      const Offset(230, 152),
      const Offset(245, 152),
      Paint()
        ..color = primaryContainer
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round,
    );

    final plantStem = Paint()
      ..color = _nexaGreen
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    canvas.drawLine(const Offset(309, 177), const Offset(309, 122), plantStem);
    canvas.drawLine(const Offset(309, 145), const Offset(291, 130), plantStem);
    canvas.drawLine(const Offset(309, 155), const Offset(327, 139), plantStem);
    canvas.drawOval(
      const Rect.fromLTWH(280, 119, 18, 10),
      Paint()..color = _nexaGreen,
    );
    canvas.drawOval(
      const Rect.fromLTWH(320, 130, 18, 10),
      Paint()..color = _nexaGreen,
    );
    final pot = Path()
      ..moveTo(297, 177)
      ..lineTo(321, 177)
      ..lineTo(317, 196)
      ..lineTo(301, 196)
      ..close();
    canvas.drawPath(pot, Paint()..color = _nexaWarm.withValues(alpha: 0.62));

    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _BuyerWelcomePainter oldDelegate) =>
      primary != oldDelegate.primary ||
      primaryContainer != oldDelegate.primaryContainer ||
      secondary != oldDelegate.secondary ||
      outline != oldDelegate.outline ||
      surface != oldDelegate.surface;
}
