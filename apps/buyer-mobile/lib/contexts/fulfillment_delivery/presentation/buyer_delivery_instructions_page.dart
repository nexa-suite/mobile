import 'package:go_router/go_router.dart';

import '../../../core/presentation/buyer_visible_route_poller.dart';
import '../../tenant_access_governance/application/buyer_change_feed_repository.dart';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/buyer_delivery_instructions_repository.dart';
import 'buyer_delivery_instructions_view_model.dart';

final class BuyerDeliveryInstructionsPage extends StatefulWidget {
  const BuyerDeliveryInstructionsPage({super.key});
  @override
  State<BuyerDeliveryInstructionsPage> createState() =>
      _BuyerDeliveryInstructionsPageState();
}

final class _BuyerDeliveryInstructionsPageState
    extends State<BuyerDeliveryInstructionsPage> {
  BuyerVisibleRoutePoller? _poller;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final model = context.read<BuyerDeliveryInstructionsViewModel>();
    _poller ??= BuyerVisibleRoutePoller(
      router: GoRouter.of(context),
      routePath: '/orders/${Uri.encodeComponent(model.orderId)}/instructions',
      onRefresh: model.load,
      changes: () => context
          .read<BuyerChangeFeedRepository>()
          .watch(readPermission: 'buyer.order.read')
          .map<void>((_) {}),
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BuyerDeliveryInstructionsViewModel>().load();
    });
  }

  Future<void> _edit(
    BuyerDeliveryInstructionsViewModel model, [
    BuyerDeliveryInstruction? instruction,
  ]) async {
    final scope = model.scope;
    final version = model.value?.version;
    final result = await showDialog<({String kind, String content})>(
      context: context,
      builder: (_) => _InstructionEditor(instruction: instruction),
    );
    if (!mounted ||
        result == null ||
        scope != model.scope ||
        version == null ||
        !model.canEdit) {
      return;
    }
    await model.save(
      result.kind,
      result.content,
      expectedVersion: version,
      instructionId: instruction?.id,
    );
  }

  @override
  Widget build(BuildContext context) =>
      Consumer<BuyerDeliveryInstructionsViewModel>(
        builder: (context, model, _) => Scaffold(
          appBar: AppBar(title: const Text('Indicaciones de entrega')),
          body: SafeArea(
            top: false,
            child: NexaPageWidth(
              child: ListView(
                children: [
                  const Text(
                    'Comparte cómo llegar, dónde descargar o qué cuidados necesita tu entrega.',
                  ),
                  const SizedBox(height: 16),
                  if (model.busy) const LinearProgressIndicator(),
                  if (model.message case final message?)
                    NexaNotice(message, isError: model.messageIsError),
                  if (model.canRetry)
                    FilledButton.tonal(
                      onPressed: model.retry,
                      child: const Text('Reintentar la misma indicación'),
                    )
                  else
                    OutlinedButton.icon(
                      onPressed: model.busy ? null : model.load,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Actualizar'),
                    ),
                  if (model.value?.editable == false)
                    const NexaNotice(
                      'La preparación ya avanzó. Las indicaciones de esta entrega son de consulta.',
                    ),
                  if (model.value != null && model.value!.items.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 24),
                      child: Text('Aún no hay indicaciones para esta entrega.'),
                    ),
                  for (final instruction
                      in model.value?.items ?? <BuyerDeliveryInstruction>[])
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text(
                              _label(instruction.kind),
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            const SizedBox(height: 8),
                            Text(instruction.content),
                            if (model.canEdit &&
                                BuyerDeliveryInstructionsViewModel
                                    .instructionKinds
                                    .contains(instruction.kind))
                              TextButton.icon(
                                onPressed: () => _edit(model, instruction),
                                icon: const Icon(Icons.edit_outlined),
                                label: const Text('Editar'),
                              ),
                          ],
                        ),
                      ),
                    ),
                  const SizedBox(height: 16),
                  if (model.canEdit)
                    FilledButton.icon(
                      onPressed: () => _edit(model),
                      icon: const Icon(Icons.add),
                      label: const Text('Agregar indicación'),
                    ),
                ],
              ),
            ),
          ),
        ),
      );
}

class _InstructionEditor extends StatefulWidget {
  const _InstructionEditor({this.instruction});
  final BuyerDeliveryInstruction? instruction;
  @override
  State<_InstructionEditor> createState() => _InstructionEditorState();
}

class _InstructionEditorState extends State<_InstructionEditor> {
  final _form = GlobalKey<FormState>();
  late final TextEditingController _content = TextEditingController(
    text: widget.instruction?.content,
  );
  late String _kind = widget.instruction?.kind ?? 'NORMAL';
  @override
  void dispose() {
    _content.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    scrollable: true,
    title: Text(
      widget.instruction == null ? 'Agregar indicación' : 'Editar indicación',
    ),
    content: Form(
      key: _form,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DropdownButtonFormField<String>(
            initialValue: _kind,
            isExpanded: true,
            decoration: const InputDecoration(
              labelText: '¿Qué debe saber el equipo?',
            ),
            items: BuyerDeliveryInstructionsViewModel.instructionKinds
                .map(
                  (kind) =>
                      DropdownMenuItem(value: kind, child: Text(_label(kind))),
                )
                .toList(),
            onChanged: (value) {
              if (value != null) _kind = value;
            },
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _content,
            minLines: 3,
            maxLines: 6,
            maxLength: 2000,
            decoration: const InputDecoration(
              labelText: 'Tu indicación',
              hintText: 'Por ejemplo: usar la entrada de carga.',
            ),
            validator: (value) => value == null || value.trim().isEmpty
                ? 'Escribe una indicación.'
                : null,
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancelar'),
      ),
      FilledButton(
        onPressed: () {
          if (_form.currentState!.validate()) {
            Navigator.pop(context, (kind: _kind, content: _content.text));
          }
        },
        child: const Text('Guardar'),
      ),
    ],
  );
}

String _label(String kind) => switch (kind) {
  'NORMAL' => 'Indicaciones generales',
  'COLD_CHAIN' => 'Conservación en frío',
  'ACCESS_RESTRICTION' => 'Acceso al lugar',
  'SPECIAL_UNLOADING' => 'Descarga',
  'CUSTOMER_SAFETY' => 'Seguridad al entregar',
  'GOODS_HANDLING' => 'Cuidado de productos',
  _ => 'Indicación de entrega',
};
