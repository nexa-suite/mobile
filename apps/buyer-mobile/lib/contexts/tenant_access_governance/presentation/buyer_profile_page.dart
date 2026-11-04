import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/buyer_profile_repository.dart';
import 'buyer_profile_view_model.dart';

class BuyerProfilePage extends StatefulWidget {
  const BuyerProfilePage({super.key});
  @override
  State<BuyerProfilePage> createState() => _BuyerProfilePageState();
}

class _BuyerProfilePageState extends State<BuyerProfilePage> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController(),
      _first = TextEditingController(),
      _last = TextEditingController(),
      _phone = TextEditingController();
  BuyerProfile? _bound;
  String _language = 'es';
  @override
  void initState() {
    super.initState();
    Future.microtask(() {
      if (mounted) context.read<BuyerProfileViewModel>().load();
    });
  }

  @override
  void dispose() {
    for (final c in [_name, _first, _last, _phone]) {
      c.dispose();
    }
    super.dispose();
  }

  void _bind(BuyerProfile? profile) {
    if (identical(_bound, profile)) return;
    _bound = profile;
    _name.text = profile?.displayName ?? '';
    _first.text = profile?.firstName ?? '';
    _last.text = profile?.lastName ?? '';
    _phone.text = profile?.phone ?? '';
    _language = profile?.language == 'en' ? 'en' : 'es';
  }

  @override
  Widget build(BuildContext context) => Consumer<BuyerProfileViewModel>(
    builder: (context, vm, _) {
      _bind(vm.profile);
      final profile = vm.profile;
      return Scaffold(
        appBar: AppBar(title: const Text('Mis datos')),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 600),
              child: ListView(
                padding: const EdgeInsets.all(24),
                children: [
                  if (vm.busy) const LinearProgressIndicator(),
                  if (vm.message case final message?) ...[
                    NexaNotice(message, isError: vm.messageIsError),
                    const SizedBox(height: 16),
                  ],
                  if (profile == null || vm.mustReload)
                    OutlinedButton.icon(
                      onPressed: vm.busy ? null : vm.load,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Actualizar mis datos'),
                    ),
                  if (profile != null) ...[
                    Card(
                      child: ListTile(
                        leading: const Icon(Icons.verified_user_outlined),
                        title: Text(profile.email),
                        subtitle: const Text(
                          'Tu empresa administra tu correo y tus permisos.',
                        ),
                      ),
                    ),
                    const SizedBox(height: 24),
                    Form(
                      key: _form,
                      child: Column(
                        children: [
                          _field(
                            _name,
                            'Nombre para mostrar',
                            160,
                            vm,
                            required: true,
                          ),
                          _field(_first, 'Nombres', 100, vm),
                          _field(_last, 'Apellidos', 100, vm),
                          TextFormField(
                            controller: _phone,
                            enabled: vm.canEdit,
                            keyboardType: TextInputType.phone,
                            autofillHints: const [
                              AutofillHints.telephoneNumber,
                            ],
                            decoration: const InputDecoration(
                              labelText: 'Teléfono',
                              hintText: '+51 999 999 999',
                              helperText:
                                  'Incluye el código de país, sin espacios.',
                            ),
                            validator: (value) =>
                                value!.trim().isEmpty ||
                                    RegExp(r'^\+[1-9][0-9]{6,14}$')
                                        .hasMatch(value.trim())
                                ? null
                                : 'Revisa el teléfono y el código de país.',
                          ),
                          const SizedBox(height: 16),
                          DropdownButtonFormField<String>(
                            initialValue: _language,
                            decoration: const InputDecoration(
                              labelText: 'Idioma preferido',
                            ),
                            items: const [
                              DropdownMenuItem(
                                value: 'es',
                                child: Text('Español'),
                              ),
                              DropdownMenuItem(
                                value: 'en',
                                child: Text('English'),
                              ),
                            ],
                            onChanged: vm.canEdit
                                ? (value) => setState(() => _language = value!)
                                : null,
                          ),
                          const SizedBox(height: 24),
                          SizedBox(
                            width: double.infinity,
                            child: FilledButton(
                              onPressed: vm.canEdit
                                  ? () {
                                      if (_form.currentState!.validate()) {
                                        vm.save(
                                          BuyerProfileEdit(
                                            displayName: _name.text,
                                            firstName: _first.text,
                                            lastName: _last.text,
                                            phone: _phone.text,
                                            language: _language,
                                          ),
                                        );
                                      }
                                    }
                                  : null,
                              child: const Text('Guardar mis datos'),
                            ),
                          ),
                        ],
                      ),
                    ),
                    if (profile.documentNumber != null) ...[
                      const SizedBox(height: 24),
                      ListTile(
                        title: Text(profile.documentType ?? 'Documento'),
                        subtitle: Text(profile.documentNumber!),
                        trailing: const Icon(Icons.lock_outline),
                      ),
                    ],
                  ],
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
  Widget _field(
    TextEditingController c,
    String label,
    int limit,
    BuyerProfileViewModel vm, {
    bool required = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: TextFormField(
      controller: c,
      enabled: vm.canEdit,
      maxLength: limit,
      textCapitalization: TextCapitalization.words,
      decoration: InputDecoration(labelText: label, counterText: ''),
      validator: (value) =>
          required && value!.trim().isEmpty ? 'Escribe tu nombre.' : null,
    ),
  );
}
