import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_profile_repository.dart';

final class BuyerProfileRepositoryImpl implements BuyerProfileRepository {
  BuyerProfileRepositoryImpl(this._api);
  final NexaApiClient _api;
  @override
  Future<BuyerProfile> load() async => _read(
    (await _api.get('/me/profile', refreshAfterUnauthorized: true)).body,
  );
  @override
  Future<BuyerProfile> update(
    BuyerProfile current,
    BuyerProfileEdit edit,
  ) async {
    final result = _read(
      (await _api.request(
        'PATCH',
        '/me/profile',
        ifMatch: '"${current.version}"',
        refreshAfterUnauthorized: true,
        body: {
          'displayName': edit.displayName.trim(),
          'firstName': _optional(edit.firstName),
          'lastName': _optional(edit.lastName),
          'phone': _optional(edit.phone),
          'preferredLanguage': edit.language,
          'timezone': current.timezone,
        },
      )).body,
    );
    if (result.userId != current.userId || result.version <= current.version) {
      _invalid();
    }
    return result;
  }

  String? _optional(String value) => value.trim().isEmpty ? null : value.trim();
  BuyerProfile _read(Map<String, Object?> body) {
    String text(String key) {
      final value = body[key];
      if (value is! String || value.trim().isEmpty) _invalid();
      return value;
    }

    String? optional(String key) =>
        body[key] is String ? body[key] as String : null;
    final version = body['version'];
    if (version is! int || version < 0) _invalid();
    return BuyerProfile(
      userId: text('userId'),
      email: text('email'),
      displayName: text('displayName'),
      language: text('preferredLanguage'),
      timezone: text('timezone'),
      version: version,
      phone: optional('phone'),
      firstName: optional('firstName'),
      lastName: optional('lastName'),
      documentType: optional('documentType'),
      documentNumber: optional('documentNumber'),
      documentCountryCode: optional('documentCountryCode'),
    );
  }

  Never _invalid() => throw const NexaApiFailure(
    code: 'profile_response_invalid',
    userMessage: 'No pudimos confirmar tus datos. Actualiza el perfil.',
  );
}
