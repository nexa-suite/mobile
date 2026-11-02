import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_addresses_repository.dart';

final class BuyerAddressesRepositoryImpl implements BuyerAddressesRepository {
  BuyerAddressesRepositoryImpl(this._api);
  final NexaApiClient _api;
  static const _path = '/buyer/addresses';
  static final _id = RegExp(r'^[0-9a-fA-F-]{36}$');
  @override
  Future<List<BuyerAddress>> list() async {
    final data = (await _api.get(_path, refreshAfterUnauthorized: true)).data;
    if (data is! List) _invalid();
    return List.unmodifiable(data.map(_read));
  }

  @override
  Future<BuyerAddress> save(
    BuyerAddressEdit edit, {
    BuyerAddress? current,
  }) async {
    if (current != null && !_id.hasMatch(current.id)) _invalid();
    final response = await _api.request(
      current == null ? 'POST' : 'PATCH',
      current == null ? _path : '$_path/${current.id}',
      ifMatch: current == null ? null : '"${current.version}"',
      body: {
        'label': edit.label,
        'address': edit.details,
        if (current == null) 'defaultAddress': false,
      },
    );
    final saved = _read(response.body);
    if (current != null &&
        (saved.id != current.id || saved.version <= current.version)) {
      _invalid();
    }
    return saved;
  }

  @override
  Future<void> makeDefault(BuyerAddress address) => _change(address, true);
  @override
  Future<void> remove(BuyerAddress address) => _change(address, false);
  Future<void> _change(BuyerAddress address, bool makeDefault) async {
    if (!_id.hasMatch(address.id)) _invalid();
    final response = await _api.request(
      makeDefault ? 'PUT' : 'DELETE',
      '$_path/${address.id}${makeDefault ? '/default' : ''}',
      ifMatch: '"${address.version}"',
    );
    final data = response.body;
    if (data['id'] != address.id ||
        data['version'] is! int ||
        (data['version'] as int) <= address.version ||
        (makeDefault
            ? data['defaultAddress'] != true
            : data['active'] != false)) {
      _invalid();
    }
  }

  @override
  Future<List<BuyerAddressOption>> options(
    String resource, {
    String? parent,
  }) async {
    if (!{
      'departments',
      'provinces',
      'districts',
      'road-types',
    }.contains(resource)) {
      _invalid();
    }
    final data = (await _api.get(
      '/reference/$resource',
      query: {'parentCode': ?parent},
      refreshAfterUnauthorized: true,
    )).data;
    if (data is! List) _invalid();
    return List.unmodifiable(
      data.where((value) => value is Map && value['active'] == true).map((
        value,
      ) {
        if (value is! Map ||
            value['code'] is! String ||
            value['label'] is! String) {
          _invalid();
        }
        return BuyerAddressOption(
          value['code'] as String,
          value['label'] as String,
        );
      }),
    );
  }

  BuyerAddress _read(Object? value) {
    if (value is! Map ||
        value['id'] is! String ||
        !_id.hasMatch(value['id'] as String) ||
        value['label'] is! String ||
        value['version'] is! int ||
        (value['version'] as int) < 0 ||
        value['defaultAddress'] is! bool) {
      _invalid();
    }
    return BuyerAddress(
      id: value['id'] as String,
      label: value['label'] as String,
      version: value['version'] as int,
      isDefault: value['defaultAddress'] as bool,
      details: {for (final key in _fields) key: value[key]},
    );
  }

  static const _fields = [
    'addressType',
    'line',
    'reference',
    'countryCode',
    'departmentCode',
    'provinceCode',
    'districtCode',
    'recipientName',
    'recipientPhone',
    'roadType',
    'streetName',
    'streetNumber',
    'interior',
    'postalCode',
    'receivingInstructions',
    'receivingHours',
    'latitude',
    'longitude',
    'placeId',
    'source',
  ];
  Never _invalid() => throw const NexaApiFailure(
    code: 'buyer_address_response_invalid',
    userMessage:
        'No pudimos confirmar la dirección. Actualiza tus direcciones.',
  );
}
