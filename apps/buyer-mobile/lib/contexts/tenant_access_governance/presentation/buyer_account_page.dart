import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../app/buyer_workspace_navigation.dart';
import '../../../core/application/error/nexa_api_failure.dart';
import '../../../core/presentation/nexa_buyer_components.dart';
import '../../../core/presentation/nexa_buyer_palette.dart';
import 'buyer_access_view_model.dart';

class BuyerAccountPage extends StatefulWidget {
  const BuyerAccountPage({super.key});

  @override
  State<BuyerAccountPage> createState() => _BuyerAccountPageState();
}

class _BuyerAccountPageState extends State<BuyerAccountPage> {
  String? _openedAuthority;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _openedAuthority ??= _authorityKey(context.read<BuyerAccessViewModel>());
  }

  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (mounted) {
        unawaited(
          context.read<BuyerAccessViewModel>().refreshBiometricAvailability(),
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) => Consumer<BuyerAccessViewModel>(
    builder: (context, access, _) {
      final buyer = access.snapshot.currentContext;
      final sameAuthority = _openedAuthority == _authorityKey(access);
      final destinations =
          BuyerWorkspaceNavigation.destinations(access.snapshot)
              .where(
                (item) => const {
                  BuyerWorkspaceDestination.credit,
                  BuyerWorkspaceDestination.documents,
                  BuyerWorkspaceDestination.wallet,
                }.contains(item),
              )
              .toList();
      final canReadFavorites = BuyerWorkspaceNavigation.canOpenLocation(
        access.snapshot,
        '/favorites',
      );
      final canReadNotifications = BuyerWorkspaceNavigation.canOpenLocation(
        access.snapshot,
        '/notifications/preferences',
      );

      return Scaffold(
        appBar: AppBar(title: const Text('Cuenta')),
        body: SafeArea(
          child: NexaPageWidth(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: ListView(
              padding: const EdgeInsets.only(top: 12, bottom: 24),
              children: [
                if (!sameAuthority)
                  const NexaNotice(
                    'Tu acceso cambió. Vuelve a abrir Cuenta para continuar.',
                    isError: true,
                  )
                else if (buyer != null) ...[
                  _BuyerIdentityCard(
                    displayName: _nonEmpty(buyer.displayName) ?? 'Mi cuenta',
                    email: _nonEmpty(buyer.email),
                    company: _nonEmpty(buyer.tenantName),
                    initial: _accountInitial(buyer.displayName, buyer.email),
                  ),
                  const SizedBox(height: 20),
                  _sectionHeading(context, 'Mi cuenta'),
                  _AccountSettingsCard(
                    children: [
                      _AccountActionTile(
                        icon: Icons.person_outline,
                        title: 'Mis datos',
                        iconColor: NexaBuyerPalette.primary,
                        iconSurface: NexaBuyerPalette.primarySurface,
                        onTap: _destinationAction(
                          context,
                          access,
                          path: '/profile',
                        ),
                      ),
                      if (BuyerWorkspaceNavigation.canOpenLocation(
                        access.snapshot,
                        '/addresses',
                      )) ...[
                        const _AccountRowDivider(),
                        _destinationTile(
                          context,
                          access,
                          path: '/addresses',
                          icon: Icons.location_on_outlined,
                          title: 'Direcciones',
                          iconColor: NexaBuyerPalette.positive,
                          iconSurface: NexaBuyerPalette.positiveSurface,
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 16),
                  _sectionHeading(context, 'Seguridad'),
                  _AccountSettingsCard(
                    children: [
                      _AccountActionTile(
                        icon: Icons.lock_outline,
                        title: 'Cambiar contraseña',
                        iconColor: NexaBuyerPalette.cold,
                        iconSurface: NexaBuyerPalette.coldSurface,
                        onTap: sameAuthority
                            ? () => _changePassword(context, access)
                            : null,
                      ),
                      const _AccountRowDivider(),
                      _AccountActionTile(
                        icon: Icons.devices_outlined,
                        title: 'Otras sesiones',
                        subtitle: 'Cerrar sesión en otros dispositivos',
                        iconColor: NexaBuyerPalette.cold,
                        iconSurface: NexaBuyerPalette.coldSurface,
                        onTap: sameAuthority
                            ? () => _revokeOtherSessions(context, access)
                            : null,
                      ),
                      const _AccountRowDivider(),
                      SwitchListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 12,
                        ),
                        secondary: const _AccountIconBadge(
                          icon: Icons.fingerprint,
                          color: NexaBuyerPalette.cold,
                          surface: NexaBuyerPalette.coldSurface,
                        ),
                        title: const Text('Acceso biométrico'),
                        subtitle: Text(
                          access.biometricSupported
                              ? 'Huella o reconocimiento facial'
                              : 'Configúralo en los ajustes del dispositivo',
                        ),
                        value: access.biometricEnabled,
                        onChanged:
                            !sameAuthority ||
                                access.biometricBusy ||
                                (!access.biometricSupported &&
                                    !access.biometricEnabled)
                            ? null
                            : access.setBiometricUnlockEnabled,
                      ),
                      if (access.biometricMessage case final message?)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                          child: NexaNotice(message, isError: true),
                        ),
                    ],
                  ),
                  if (canReadNotifications) ...[
                    const SizedBox(height: 16),
                    _sectionHeading(context, 'Preferencias'),
                    _AccountSettingsCard(
                      children: [
                        _destinationTile(
                          context,
                          access,
                          path: '/notifications/preferences',
                          icon: Icons.notifications_outlined,
                          title: 'Avisos',
                          subtitle: 'Preferencias de notificaciones',
                          iconColor: NexaBuyerPalette.attention,
                          iconSurface: NexaBuyerPalette.attentionSurface,
                        ),
                      ],
                    ),
                  ],
                  if (destinations.isNotEmpty || canReadFavorites) ...[
                    const SizedBox(height: 16),
                    _AccountSettingsCard(
                      children: [
                        ExpansionTile(
                          tilePadding: const EdgeInsets.symmetric(
                            horizontal: 12,
                          ),
                          leading: const _AccountIconBadge(
                            icon: Icons.grid_view_outlined,
                            color: NexaBuyerPalette.muted,
                            surface: NexaBuyerPalette.frozenSurface,
                          ),
                          title: const Text('Otros servicios'),
                          children: [
                            for (final destination in destinations)
                              _AccountActionTile(
                                icon: _destinationIcon(destination),
                                title: destination.label,
                                iconColor: NexaBuyerPalette.primary,
                                iconSurface: NexaBuyerPalette.primarySurface,
                                onTap:
                                    sameAuthority &&
                                        BuyerWorkspaceNavigation.destinations(
                                          access.snapshot,
                                        ).contains(destination)
                                    ? () => _openBranch(destination, access)
                                    : null,
                              ),
                            if (canReadFavorites) ...[
                              if (destinations.isNotEmpty)
                                const _AccountRowDivider(),
                              _destinationTile(
                                context,
                                access,
                                path: '/favorites',
                                icon: Icons.favorite_border,
                                title: 'Favoritos',
                                iconColor: NexaBuyerPalette.primary,
                                iconSurface: NexaBuyerPalette.primarySurface,
                              ),
                            ],
                          ],
                        ),
                      ],
                    ),
                  ],
                ],
                const SizedBox(height: 20),
                OutlinedButton.icon(
                  onPressed: access.busy || !sameAuthority
                      ? null
                      : () => access.signOut(),
                  icon: const Icon(Icons.logout),
                  label: const Text('Cerrar sesión'),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );

  Widget _sectionHeading(BuildContext context, String title) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Text(
      title,
      style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontSize: 16),
    ),
  );

  Widget _destinationTile(
    BuildContext context,
    BuyerAccessViewModel access, {
    required String path,
    required IconData icon,
    required String title,
    required Color iconColor,
    required Color iconSurface,
    String? subtitle,
  }) => _AccountActionTile(
    icon: icon,
    title: title,
    subtitle: subtitle,
    iconColor: iconColor,
    iconSurface: iconSurface,
    onTap: _destinationAction(context, access, path: path),
  );

  VoidCallback? _destinationAction(
    BuildContext context,
    BuyerAccessViewModel access, {
    required String path,
  }) =>
      _openedAuthority == _authorityKey(access) &&
          BuyerWorkspaceNavigation.canOpenLocation(access.snapshot, path)
      ? () => context.push(path)
      : null;

  void _openBranch(
    BuyerWorkspaceDestination destination,
    BuyerAccessViewModel access,
  ) {
    final path = _branchPath(destination);
    if (_openedAuthority == _authorityKey(access) &&
        BuyerWorkspaceNavigation.canOpenLocation(access.snapshot, path)) {
      context.go(path);
    }
  }

  String _branchPath(BuyerWorkspaceDestination destination) => destination.path;

  Future<void> _changePassword(
    BuildContext context,
    BuyerAccessViewModel access,
  ) async {
    final authority = _authorityKey(access);
    final currentPassword = TextEditingController();
    final newPassword = TextEditingController();
    final confirmation = TextEditingController();
    var busy = false;
    String? error;

    try {
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => StatefulBuilder(
          builder: (dialogContext, setDialogState) => AlertDialog(
            title: const Text('Cambiar contraseña'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: currentPassword,
                    obscureText: true,
                    autofocus: true,
                    onChanged: (_) => setDialogState(() {}),
                    decoration: const InputDecoration(
                      labelText: 'Contraseña actual',
                    ),
                  ),
                  TextField(
                    controller: newPassword,
                    obscureText: true,
                    onChanged: (_) => setDialogState(() {}),
                    decoration: const InputDecoration(
                      labelText: 'Nueva contraseña',
                    ),
                  ),
                  TextField(
                    controller: confirmation,
                    obscureText: true,
                    onChanged: (_) => setDialogState(() {}),
                    decoration: const InputDecoration(
                      labelText: 'Confirmar contraseña',
                    ),
                  ),
                  if (error case final message?)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        message,
                        style: TextStyle(
                          color: Theme.of(dialogContext).colorScheme.error,
                        ),
                      ),
                    ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: busy ? null : () => Navigator.pop(dialogContext),
                child: const Text('Cancelar'),
              ),
              FilledButton(
                onPressed:
                    busy ||
                        currentPassword.text.isEmpty ||
                        newPassword.text.isEmpty ||
                        confirmation.text.isEmpty
                    ? null
                    : () async {
                        if (newPassword.text != confirmation.text) {
                          setDialogState(() {
                            error = 'Las contraseñas nuevas no coinciden.';
                          });
                          return;
                        }
                        if (_authorityKey(access) != authority) return;
                        setDialogState(() {
                          busy = true;
                          error = null;
                        });
                        try {
                          await access.changePassword(
                            currentPassword: currentPassword.text,
                            newPassword: newPassword.text,
                          );
                          if (dialogContext.mounted) {
                            Navigator.pop(dialogContext);
                          }
                          if (context.mounted &&
                              _authorityKey(access) == authority) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text('Contraseña actualizada.'),
                              ),
                            );
                          }
                        } on NexaApiFailure catch (failure) {
                          if (dialogContext.mounted) {
                            setDialogState(() {
                              error = failure.userMessage;
                            });
                          }
                        } catch (_) {
                          if (dialogContext.mounted) {
                            setDialogState(() {
                              error = 'No se pudo cambiar la contraseña. Inténtalo de nuevo.';
                            });
                          }
                        } finally {
                          if (dialogContext.mounted) {
                            setDialogState(() => busy = false);
                          }
                        }
                      },
                child: busy
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Guardar'),
              ),
            ],
          ),
        ),
      );
    } finally {
      currentPassword.dispose();
      newPassword.dispose();
      confirmation.dispose();
    }
  }

  Future<void> _revokeOtherSessions(
    BuildContext context,
    BuyerAccessViewModel access,
  ) async {
    final authority = _authorityKey(access);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Cerrar otras sesiones'),
        content: const Text(
          'Se cerrarán tus sesiones en otros dispositivos. Esta sesión seguirá activa.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Cerrar sesiones'),
          ),
        ],
      ),
    );
    if (confirmed != true ||
        !context.mounted ||
        _authorityKey(access) != authority) {
      return;
    }
    try {
      await access.revokeOtherSessions();
      if (context.mounted && _authorityKey(access) == authority) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Otras sesiones cerradas.')),
        );
      }
    } on NexaApiFailure catch (failure) {
      if (context.mounted && _authorityKey(access) == authority) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(failure.userMessage)));
      }
    } catch (_) {
      if (context.mounted && _authorityKey(access) == authority) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('No se pudieron cerrar las otras sesiones.'),
          ),
        );
      }
    }
  }
}

class _BuyerIdentityCard extends StatelessWidget {
  const _BuyerIdentityCard({
    required this.displayName,
    required this.initial,
    this.email,
    this.company,
  });

  final String displayName;
  final String initial;
  final String? email;
  final String? company;

  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    elevation: 0,
    surfaceTintColor: Colors.transparent,
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(
            radius: 26,
            backgroundColor: NexaBuyerPalette.primarySurface,
            foregroundColor: NexaBuyerPalette.primary,
            child: Text(initial, style: Theme.of(context).textTheme.titleLarge),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  displayName,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                if (email != null) ...[
                  const SizedBox(height: 6),
                  _IdentityDetail(
                    icon: Icons.mail_outline,
                    label: 'Correo',
                    value: email!,
                  ),
                ],
                if (company != null) ...[
                  const SizedBox(height: 4),
                  _IdentityDetail(
                    icon: Icons.business_outlined,
                    label: 'Empresa',
                    value: company!,
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _IdentityDetail extends StatelessWidget {
  const _IdentityDetail({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Icon(icon, size: 16, color: NexaBuyerPalette.muted),
      const SizedBox(width: 6),
      Expanded(
        child: Text.rich(
          TextSpan(
            children: [
              TextSpan(
                text: '$label · ',
                style: Theme.of(context).textTheme.labelSmall
                    ?.copyWith(color: NexaBuyerPalette.muted),
              ),
              TextSpan(
                text: value,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    ],
  );
}

class _AccountSettingsCard extends StatelessWidget {
  const _AccountSettingsCard({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    elevation: 0,
    surfaceTintColor: Colors.transparent,
    clipBehavior: Clip.antiAlias,
    child: Column(mainAxisSize: MainAxisSize.min, children: children),
  );
}

class _AccountActionTile extends StatelessWidget {
  const _AccountActionTile({
    required this.icon,
    required this.title,
    required this.iconColor,
    required this.iconSurface,
    required this.onTap,
    this.subtitle,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final Color iconColor;
  final Color iconSurface;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 56),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            _AccountIconBadge(
              icon: icon,
              color: iconColor,
              surface: iconSurface,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: Theme.of(context).textTheme.titleMedium),
                  if (subtitle != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      subtitle!,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(Icons.chevron_right, color: NexaBuyerPalette.muted),
          ],
        ),
      ),
    ),
  );
}

class _AccountIconBadge extends StatelessWidget {
  const _AccountIconBadge({
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

class _AccountRowDivider extends StatelessWidget {
  const _AccountRowDivider();

  @override
  Widget build(BuildContext context) => Divider(
    height: 1,
    indent: 64,
    endIndent: 12,
    color: NexaBuyerPalette.line,
  );
}

String? _nonEmpty(String? value) {
  final result = value?.trim();
  return result == null || result.isEmpty ? null : result;
}

String _accountInitial(String? displayName, String? email) {
  final value = _nonEmpty(displayName) ?? _nonEmpty(email) ?? '';
  return value.isEmpty
      ? 'N'
      : String.fromCharCode(value.runes.first).toUpperCase();
}

String _authorityKey(BuyerAccessViewModel access) =>
    '${access.snapshot.authorityEpoch}:${access.snapshot.currentContext?.authorityFingerprint}';

IconData _destinationIcon(BuyerWorkspaceDestination destination) =>
    switch (destination) {
      BuyerWorkspaceDestination.home => Icons.home_outlined,
      BuyerWorkspaceDestination.requests => Icons.shopping_bag_outlined,
      BuyerWorkspaceDestination.catalog => Icons.inventory_2_outlined,
      BuyerWorkspaceDestination.orders => Icons.receipt_long_outlined,
      BuyerWorkspaceDestination.credit => Icons.account_balance_wallet_outlined,
      BuyerWorkspaceDestination.documents => Icons.description_outlined,
      BuyerWorkspaceDestination.wallet => Icons.account_balance_wallet_outlined,
      BuyerWorkspaceDestination.deliveries => Icons.local_shipping_outlined,
    };
