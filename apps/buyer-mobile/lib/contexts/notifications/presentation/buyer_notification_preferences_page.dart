import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../application/buyer_notifications_repository.dart';
import 'buyer_notifications_view_model.dart';

final class BuyerNotificationPreferencesPage extends StatefulWidget {
  const BuyerNotificationPreferencesPage({super.key});

  @override
  State<BuyerNotificationPreferencesPage> createState() =>
      _BuyerNotificationPreferencesPageState();
}

final class _BuyerNotificationPreferencesPageState
    extends State<BuyerNotificationPreferencesPage> {
  @override
  void initState() {
    super.initState();
    unawaited(
      context.read<BuyerNotificationsViewModel>().refreshPreferences(),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Preferencias de notificaciones')),
    body: Consumer<BuyerNotificationsViewModel>(
      builder: (context, viewModel, _) => RefreshIndicator(
        onRefresh: viewModel.refreshPreferences,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          children: [
            if (!viewModel.canManagePreferences)
              const Card(
                child: ListTile(
                  leading: Icon(Icons.info_outline),
                  title: Text('Preferencias en modo de lectura'),
                  subtitle: Text(
                    'Tu acceso actual permite consultar, pero no cambiar estas preferencias.',
                  ),
                ),
              ),
            if (viewModel.preferencesMessage != null)
              Card(
                color: Theme.of(context).colorScheme.errorContainer,
                child: ListTile(
                  title: Text(viewModel.preferencesMessage!),
                  trailing: IconButton(
                    tooltip: 'Actualizar',
                    onPressed: () => unawaited(
                      viewModel.refreshPreferences(),
                    ),
                    icon: const Icon(Icons.refresh),
                  ),
                ),
              ),
            if (viewModel.preferencesStatus ==
                    BuyerNotificationsStatus.loading &&
                viewModel.notificationPreferences == null)
              const Padding(
                padding: EdgeInsets.all(36),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (viewModel.preferencesStatus ==
                    BuyerNotificationsStatus.current &&
                _visiblePreferences(viewModel.notificationPreferences!).isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 40),
                child: Center(
                  child: Text(
                    'No hay preferencias disponibles para la app o el correo.',
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
            for (final preference in _visiblePreferences(
              viewModel.notificationPreferences,
            ))
              Card(
                margin: const EdgeInsets.only(top: 10),
                child: SwitchListTile.adaptive(
                  value: preference.enabled,
                  onChanged: viewModel.canManagePreferences &&
                          viewModel.pendingPreferenceKeys.isEmpty
                      ? (enabled) => unawaited(
                          viewModel.setPreference(preference, enabled),
                        )
                      : null,
                  title: Text(_categoryLabel(preference.eventCategory)),
                  subtitle: Text(_channelLabel(preference.channel)),
                  secondary: viewModel.pendingPreferenceKeys.isNotEmpty
                      ? const SizedBox.square(
                          dimension: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : null,
                ),
              ),
          ],
        ),
      ),
    ),
  );

  List<BuyerNotificationPreferenceProjection> _visiblePreferences(
    BuyerNotificationPreferencesProjection? projection,
  ) =>
      projection?.preferences
          .where((preference) => _channelLabelOrNull(preference.channel) != null)
          .toList(growable: false) ??
      const [];

  String _categoryLabel(String value) => value
      .split('_')
      .where((part) => part.isNotEmpty)
      .map((part) => '${part[0]}${part.substring(1).toLowerCase()}')
      .join(' ');

  String _channelLabel(String channel) =>
      _channelLabelOrNull(channel) ?? 'Canal no disponible';

  String? _channelLabelOrNull(String channel) => switch (channel) {
    'IN_APP' => 'En la app',
    'EMAIL' => 'Correo',
    _ => null,
  };
}
