import '../../../core/network/nexa_api_client.dart';
import '../application/credit_exposure_repository.dart';

final class BuyerCreditExposureRepositoryImpl
    implements BuyerCreditExposureRepository {
  BuyerCreditExposureRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<BuyerCreditExposureProjection> readCurrentBuyerExposure({
    required String currency,
  }) async {
    if (!RegExp(r'^[A-Za-z]{3}$').hasMatch(currency)) {
      _invalidResponse();
    }
    final response = await _api.get(
      '/client-accounts/me/credit-exposure',
      query: {'currency': currency.toUpperCase()},
      refreshAfterUnauthorized: true,
    );
    final body = response.body;
    final accountId = _string(body['clientAccountId']);
    final returnedCurrency = _string(body['currency']);
    final active = body['active'];
    if (accountId == null ||
        !_isUuid(accountId) ||
        returnedCurrency == null ||
        !RegExp(r'^[A-Z]{3}$').hasMatch(returnedCurrency) ||
        active is! bool) {
      _invalidResponse();
    }
    return BuyerCreditExposureProjection(
      clientAccountId: accountId,
      currency: returnedCurrency,
      creditLimit: _amount(body['creditLimit']),
      ledgerExposure: _amount(body['ledgerExposure']),
      outstandingReceivables: _amount(body['outstandingReceivables']),
      reservedExposure: _amount(body['reservedExposure']),
      used: _amount(body['used']),
      availableCredit: _amount(body['availableCredit']),
      active: active,
      asOf: DateTime.tryParse(_string(body['asOf']) ?? ''),
    );
  }

  String _amount(Object? value) {
    if (value is num && value.isFinite) return value.toString();
    if (value is String && num.tryParse(value)?.isFinite == true) return value;
    return _invalidResponse();
  }

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    caseSensitive: false,
  ).hasMatch(value);

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_credit_exposure_response',
    userMessage: 'The credit information could not be read.',
  );
}
