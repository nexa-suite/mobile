import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import 'buyer_conversation_view_model.dart';

final class BuyerConversationPage extends StatefulWidget {
  const BuyerConversationPage({super.key});
  @override
  State<BuyerConversationPage> createState() => _BuyerConversationPageState();
}

final class _BuyerConversationPageState extends State<BuyerConversationPage> {
  final _text = TextEditingController();
  BuyerVisibleRoutePoller? _poller;
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (mounted) context.read<BuyerConversationViewModel>().load();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _poller ??= BuyerVisibleRoutePoller(
      router: GoRouter.of(context),
      routePath: '/messages',
      onRefresh: context.read<BuyerConversationViewModel>().load,
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Consumer<BuyerConversationViewModel>(
    builder: (context, model, _) => Scaffold(
      appBar: AppBar(title: const Text('Mensajes con ventas')),
      body: SafeArea(
        child: Column(
          children: [
            if (model.message case final message?)
              Padding(padding: const EdgeInsets.all(12), child: Text(message)),
            Expanded(
              child: RefreshIndicator(
                onRefresh: model.load,
                child: ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.all(16),
                  children: [
                    if (model.initialized && model.items.isEmpty)
                      const Text('Aún no hay mensajes.'),
                    for (final item in model.items)
                      Card(
                        child: ListTile(
                          title: Text(
                            item.senderKind == 'BUYER' ? 'Tú' : 'Ventas',
                          ),
                          subtitle: Text(item.body),
                        ),
                      ),
                    if (model.hasMore && model.initialized)
                      TextButton(
                        onPressed: model.load,
                        child: const Text('Cargar más mensajes'),
                      ),
                  ],
                ),
              ),
            ),
            if (model.pending case final pending?)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  children: [
                    const Text('Envío pendiente de confirmación:'),
                    Text(pending.body),
                    FilledButton(
                      onPressed: model.canSend && !model.sending
                          ? () async {
                              final confirmed = await model.send('');
                              if (mounted && confirmed) {
                                _text.clear();
                              }
                            }
                          : null,
                      child: const Text('Reintentar el mismo mensaje'),
                    ),
                  ],
                ),
              )
            else if (model.canSend)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _text,
                        maxLength: 4000,
                        minLines: 1,
                        maxLines: 4,
                        decoration: const InputDecoration(labelText: 'Mensaje'),
                      ),
                    ),
                    IconButton(
                      onPressed: model.initialized && !model.sending
                          ? () async {
                              final body = _text.text;
                              final confirmed = await model.send(body);
                              if (mounted && confirmed) {
                                _text.clear();
                              }
                            }
                          : null,
                      icon: const Icon(Icons.send),
                      tooltip: 'Enviar',
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    ),
  );
}
