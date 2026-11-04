import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/buyer_addresses_repository.dart';
import 'buyer_addresses_view_model.dart';

typedef BuyerAddressLocation = ({double latitude, double longitude});
typedef BuyerAddressLocationPicker = Future<BuyerAddressLocation?> Function(
  BuildContext,
  BuyerAddressLocation?,
);

class BuyerAddressEditor extends StatefulWidget {
  const BuyerAddressEditor({
    super.key,
    required this.address,
    required this.scope,
    this.chooseLocation,
  });
  final BuyerAddress? address;
  final String? scope;
  final BuyerAddressLocationPicker? chooseLocation;
  @override
  State<BuyerAddressEditor> createState() => _BuyerAddressEditorState();
}

class _BuyerAddressEditorState extends State<BuyerAddressEditor> {
  final _form = GlobalKey<FormState>();
  final Map<String, TextEditingController> _fields = {};
  List<BuyerAddressOption> _departments = [],
      _provinces = [],
      _districts = [],
      _roads = [];
  String? _department, _province, _district, _road, _error;
  BuyerAddressLocation? _location;
  bool _loading = true, _locationChanged = false;
  int _request = 0;
  static const _labels = {
    'label': 'Nombre de la dirección',
    'line': 'Dirección completa',
    'reference': 'Referencia para llegar',
    'recipientName': 'Persona que recibe',
    'recipientPhone': 'Teléfono de contacto',
    'streetName': 'Nombre de la vía',
    'streetNumber': 'Número',
    'interior': 'Interior, piso o departamento',
    'postalCode': 'Código postal',
    'receivingInstructions': 'Indicaciones para la entrega',
    'receivingHours': 'Horario de recepción',
  };
  @override
  void initState() {
    super.initState();
    for (final key in _labels.keys) {
      _fields[key] = TextEditingController(
        text: key == 'label'
            ? widget.address?.label
            : widget.address?.text(key),
      );
    }
    final address = widget.address;
    _department = address?.text('departmentCode');
    _province = address?.text('provinceCode');
    _district = address?.text('districtCode');
    _road = address?.text('roadType');
    double? number(String key) =>
        double.tryParse('${address?.details[key] ?? ''}');
    final latitude = number('latitude'), longitude = number('longitude');
    if (latitude != null && longitude != null) {
      _location = (latitude: latitude, longitude: longitude);
    }
    Future.microtask(_loadOptions);
  }

  @override
  void dispose() {
    _request++;
    for (final field in _fields.values) {
      field.dispose();
    }
    super.dispose();
  }

  bool _same(BuyerAddressesViewModel vm) =>
      mounted && widget.scope != null && widget.scope == vm.scope;
  Future<void> _loadOptions() async {
    if (!mounted) return;
    final vm = context.read<BuyerAddressesViewModel>(), request = ++_request;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final departments = await vm.options('departments');
      final roads = await vm.options('road-types');
      final provinces = _department?.isNotEmpty == true
          ? await vm.options('provinces', parent: _department)
          : <BuyerAddressOption>[];
      final districts = _province?.isNotEmpty == true
          ? await vm.options('districts', parent: _province)
          : <BuyerAddressOption>[];
      if (!_same(vm) || request != _request) return;
      setState(() {
        _departments = departments;
        _roads = roads;
        _provinces = provinces;
        _districts = districts;
        if (!_contains(departments, _department)) _department = null;
        if (!_contains(provinces, _province)) _province = null;
        if (!_contains(districts, _district)) _district = null;
        if (!_contains(roads, _road)) _road = null;
      });
    } catch (_) {
      if (_same(vm) && request == _request) {
        setState(
          () => _error =
              'No pudimos cargar las ubicaciones. Vuelve a intentarlo.',
        );
      }
    } finally {
      if (_same(vm) && request == _request) setState(() => _loading = false);
    }
  }

  bool _contains(List<BuyerAddressOption> options, String? value) =>
      options.any((o) => o.code == value);
  Future<void> _parentChanged(bool department, String? value) async {
    setState(() {
      if (department) {
        _department = value;
        _province = null;
        _provinces = [];
      } else {
        _province = value;
      }
      _district = null;
      _districts = [];
    });
    await _loadOptions();
  }

  @override
  Widget build(BuildContext context) => Consumer<BuyerAddressesViewModel>(
    builder: (context, vm, _) {
      final same = _same(vm), enabled = same && vm.canEdit && !_loading;
      return Scaffold(
        appBar: AppBar(
          title: Text(
            widget.address == null ? 'Agregar dirección' : 'Editar dirección',
          ),
        ),
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 600),
              child: ListView(
                padding: const EdgeInsets.all(24),
                children: [
                  if (!same)
                    const NexaNotice(
                      'Tu acceso cambió. Vuelve a tus direcciones.',
                      isError: true,
                    )
                  else ...[
                    if (vm.busy || _loading) const LinearProgressIndicator(),
                    if (_error case final error?) ...[
                      NexaNotice(error, isError: true),
                      TextButton(
                        onPressed: _loadOptions,
                        child: const Text('Volver a cargar ubicaciones'),
                      ),
                    ],
                    if (vm.messageIsError && vm.message != null)
                      NexaNotice(vm.message!, isError: true),
                    if (vm.mustReload)
                      TextButton(
                        onPressed: vm.busy
                            ? null
                            : () => Navigator.pop(context),
                        child: const Text('Volver y actualizar direcciones'),
                      ),
                    Form(
                      key: _form,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _field('label', enabled, required: true, max: 120),
                          const Text('Ubicación en Perú'),
                          const SizedBox(height: 16),
                          _select(
                            'Departamento',
                            _departments,
                            _department,
                            enabled,
                            (v) => _parentChanged(true, v),
                          ),
                          _select(
                            'Provincia',
                            _provinces,
                            _province,
                            enabled,
                            (v) => _parentChanged(false, v),
                          ),
                          _select(
                            'Distrito',
                            _districts,
                            _district,
                            enabled,
                            (v) => setState(() => _district = v),
                          ),
                          _select(
                            'Tipo de vía',
                            _roads,
                            _road,
                            enabled,
                            (v) => setState(() => _road = v),
                          ),
                          _field('line', enabled, required: true, max: 240),
                          _field('reference', enabled, max: 500),
                          if (widget.chooseLocation != null) ...[
                            OutlinedButton.icon(
                              onPressed: enabled
                                  ? () async {
                                      final point = await widget
                                          .chooseLocation!(context, _location);
                                      if (_same(vm) && point != null) {
                                        setState(() {
                                          _location = point;
                                          _locationChanged = true;
                                        });
                                      }
                                    }
                                  : null,
                              icon: const Icon(Icons.map_outlined),
                              label: Text(
                                _location == null
                                    ? 'Ubicar en el mapa'
                                    : 'Cambiar punto en el mapa',
                              ),
                            ),
                            if (_location != null)
                              const Text('Punto de entrega seleccionado.'),
                            const SizedBox(height: 16),
                          ],
                          ExpansionTile(
                            title: const Text('Detalles del domicilio'),
                            children: [
                              _field('streetName', enabled, max: 180),
                              _field('streetNumber', enabled, max: 32),
                              _field('interior', enabled, max: 64),
                              _field('postalCode', enabled, max: 32),
                            ],
                          ),
                          ExpansionTile(
                            title: const Text('Contacto e indicaciones'),
                            initiallyExpanded: true,
                            children: [
                              _field('recipientName', enabled, max: 160),
                              _field(
                                'recipientPhone',
                                enabled,
                                max: 48,
                                phone: true,
                              ),
                              _field(
                                'receivingInstructions',
                                enabled,
                                max: 1000,
                              ),
                              _field('receivingHours', enabled, max: 240),
                            ],
                          ),
                          const SizedBox(height: 24),
                          FilledButton(
                            onPressed: enabled ? _save : null,
                            child: const Text('Guardar dirección'),
                          ),
                        ],
                      ),
                    ),
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
    String key,
    bool enabled, {
    bool required = false,
    int max = 160,
    bool phone = false,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: TextFormField(
      controller: _fields[key],
      enabled: enabled,
      maxLength: max,
      keyboardType: phone ? TextInputType.phone : TextInputType.text,
      decoration: InputDecoration(labelText: _labels[key], counterText: ''),
      validator: (value) =>
          required && value!.trim().isEmpty ? 'Completa este dato.' : null,
    ),
  );
  Widget _select(
    String label,
    List<BuyerAddressOption> options,
    String? value,
    bool enabled,
    ValueChanged<String?> changed,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: DropdownButtonFormField<String>(
      key: ValueKey('$label|$value|${options.length}'),
      initialValue: _contains(options, value) ? value : null,
      isExpanded: true,
      decoration: InputDecoration(labelText: label),
      items: options
          .map((o) => DropdownMenuItem(value: o.code, child: Text(o.label)))
          .toList(),
      onChanged: enabled && options.isNotEmpty ? changed : null,
      validator: (value) => value == null ? 'Selecciona una opción.' : null,
    ),
  );
  Future<void> _save() async {
    if (!_form.currentState!.validate()) return;
    final vm = context.read<BuyerAddressesViewModel>();
    if (!_same(vm) || !vm.canEdit) return;
    final details = <String, Object?>{
      ...?widget.address?.details,
      for (final key in _labels.keys.where((key) => key != 'label'))
        key: _fields[key]!.text.trim().isEmpty
            ? null
            : _fields[key]!.text.trim(),
      'addressType': _road,
      'roadType': _road,
      'countryCode': 'PE',
      'departmentCode': _department,
      'provinceCode': _province,
      'districtCode': _district,
      if (_location != null)
        'latitude': double.parse(_location!.latitude.toStringAsFixed(7)),
      if (_location != null)
        'longitude': double.parse(_location!.longitude.toStringAsFixed(7)),
      if (_locationChanged) 'placeId': null,
      'source': _locationChanged
          ? 'MAP_PIN'
          : widget.address?.text('source') ?? 'MANUAL',
    };
    final saved = await vm.save(
      BuyerAddressEdit(label: _fields['label']!.text.trim(), details: details),
      current: widget.address,
    );
    if (mounted && _same(vm) && saved) Navigator.pop(context);
  }
}
