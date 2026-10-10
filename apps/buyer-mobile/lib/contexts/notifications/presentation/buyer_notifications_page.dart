import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'buyer_notifications_view_model.dart';

final class BuyerNotificationsPage extends StatefulWidget {
  const BuyerNotificationsPage({super.key});

  @override
  State<BuyerNotificationsPage> createState() =>
      _BuyerNotificationsPageState();
}

final class _BuyerNotificationsPageState extends State<BuyerNotificationsPage> {
  @override
  void initState() {
    super.initState();
    unawaited(context.read<BuyerNotificationsViewModel>().refreshInbox());
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Notificaciones'),
      actions: [
        if (context.select<BuyerNotificationsViewModel, bool>(
          (viewModel) => viewModel.canRead,
        ))
          IconButton(
            tooltip: 'Preferencias',
            onPressed: () => context.push('/notifications/preferences'),
            icon: const Icon(Icons.tune),
          ),
      ],
    ),
    body: Consumer<BuyerNotificationsViewModel>(
      builder: (context, viewModel, _) => RefreshIndicator(
        onRefresh: viewModel.refreshInbox,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          children: [
            Row(
              children: [
                Expanded(
                  child: SegmentedButton<BuyerNotificationFilter>(
                    segments: const [
                      ButtonSegment(
                        value: BuyerNotificationFilter.all,
                        label: Text('Todas'),
                      ),
                      ButtonSegment(
                        value: BuyerNotificationFilter.unread,
                        label: Text('Sin leer'),
                      ),
                    ],
                    selected: {viewModel.filter},
                    onSelectionChanged: (selected) => unawaited(
                      viewModel.setFilter(selected.first),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Tooltip(
                  message: 'Notificaciones sin leer',
                  child: Chip(
                    avatar: const Icon(Icons.mark_email_unread_outlined),
                    label: Text('${viewModel.unreadCount}'),
                  ),
                ),
              ],
            ),
            if (viewModel.unreadCount > 0)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: viewModel.markAllPending
                      ? null
                      : () => unawaited(viewModel.markAllRead()),
                  icon: const Icon(Icons.done_all),
                  label: Text(
                    viewModel.markAllPending
                        ? 'Marcando…'
                        : 'Marcar todas como leídas',
                  ),
                ),
              ),
            if (viewModel.inboxMessage != null)
              _StatusCard(
                message: viewModel.inboxMessage!,
                loading: viewModel.inboxStatus ==
                    BuyerNotificationsStatus.loading,
                onRetry: () => unawaited(viewModel.refreshInbox()),
              ),
            if (viewModel.inboxStatus == BuyerNotificationsStatus.loading &&
                viewModel.notifications.isEmpty)
              const Padding(
                padding: EdgeInsets.all(36),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (viewModel.notifications.isEmpty &&
                viewModel.inboxStatus == BuyerNotificationsStatus.current)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 40),
                child: Center(
                  child: Text(
                    viewModel.filter == BuyerNotificationFilter.unread
                        ? 'No tienes notificaciones sin leer.'
                        : 'Todavía no tienes notificaciones.',
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
            for (final notification in viewModel.notifications)
              Card(
                margin: const EdgeInsets.only(top: 10),
                child: ListTile(
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  leading: Icon(
                    notification.isRead
                        ? Icons.mark_email_read_outlined
                        : Icons.mark_email_unread_outlined,
                    color: notification.isRead
                        ? Theme.of(context).colorScheme.onSurfaceVariant
                        : Theme.of(context).colorScheme.primary,
                  ),
                  title: Text(notification.title),
                  subtitle: Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(notification.message),
                        const SizedBox(height: 6),
                        Text(
                          '${notification.category} · ${_createdAt(context, notification.createdAt)}',
                          style: Theme.of(context).textTheme.labelSmall,
                        ),
                      ],
                    ),
                  ),
                  trailing: IconButton(
                    tooltip: notification.isRead
                        ? 'Marcar como sin leer'
                        : 'Marcar como leída',
                    onPressed: viewModel.markAllPending ||
                            viewModel.pendingNotificationIds.isNotEmpty
                        ? null
                        : () => unawaited(
                            viewModel.setRead(notification, !notification.isRead),
                          ),
                    icon: const Icon(Icons.check_circle_outline),
                  ),
                ),
              ),
          ],
        ),
      ),
    ),
  );

  String _createdAt(BuildContext context, DateTime value) {
    final local = value.toLocal();
    final date = MaterialLocalizations.of(context).formatMediumDate(local);
    final time = TimeOfDay.fromDateTime(local).format(context);
    return '$date · $time';
  }
}

final class _StatusCard extends StatelessWidget {
  const _StatusCard({
    required this.message,
    required this.loading,
    required this.onRetry,
  });

  final String message;
  final bool loading;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Card(
    color: Theme.of(context).colorScheme.errorContainer,
    child: Padding(
      padding: const EdgeInsets.all(12),
      child: Row(
        children: [
          Expanded(child: Text(message)),
          if (loading)
            const SizedBox.square(
              dimension: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else
            IconButton(
              tooltip: 'Actualizar',
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
            ),
        ],
      ),
    ),
  );
}
