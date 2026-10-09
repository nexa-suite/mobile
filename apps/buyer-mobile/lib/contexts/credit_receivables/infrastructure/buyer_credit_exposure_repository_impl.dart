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
      creditLimit: _requiredAmount(body['creditLimit']),
      ledgerExposure: _requiredAmount(body['ledgerExposure']),
      outstandingReceivables: _requiredAmount(body['outstandingReceivables']),
      reservedExposure: _requiredAmount(body['reservedExposure']),
      used: _requiredAmount(body['used']),
      availableCredit: _requiredAmount(body['availableCredit']),
      active: active,
      asOf: DateTime.tryParse(_string(body['asOf']) ?? ''),
    );
  }

  @override
  Future<BuyerReceivablesPageProjection> listBuyerReceivables({
    required int page,
  }) async {
    if (page < 0) _invalidReceivablesResponse();
    final response = await _api.get(
      '/receivables',
      query: {'page': '$page', 'size': '25'},
      refreshAfterUnauthorized: true,
    );
    final body = response.body;
    final rawItems = body['items'];
    final returnedPage = _integer(body['page']);
    final size = _integer(body['size']);
    final total = _integer(body['total']);
    if (rawItems is! List ||
        returnedPage != page ||
        size == null ||
        size < 1 ||
        size > 100 ||
        total == null ||
        total < 0) {
      _invalidReceivablesResponse();
    }
    final items = rawItems.map(_parseReceivable).toList(growable: false);
    if (items.length > size) _invalidReceivablesResponse();
    return BuyerReceivablesPageProjection(
      items: items,
      page: returnedPage!,
      size: size,
      total: total,
    );
  }

  BuyerReceivableProjection _parseReceivable(Object? value) {
    if (value is! Map<String, Object?>) _invalidReceivablesResponse();
    final id = _string(value['id']);
    final accountId = _string(value['clientAccountId']);
    final number = _string(value['number']);
    final currency = _string(value['currency']);
    final status = _string(value['status']);
    final version = _integer(value['version']);
    final amount = _amountOrNull(value['amount']);
    final amountPaid = _amountOrNull(value['amountPaid']);
    final remaining = _amountOrNull(value['remaining']);
    final rawDueAt = value['dueAt'];
    final dueAt = rawDueAt == null
        ? null
        : DateTime.tryParse(rawDueAt is String ? rawDueAt : '');
    if (id == null ||
        !_isUuid(id) ||
        accountId == null ||
        !_isUuid(accountId) ||
        number == null ||
        currency == null ||
        !RegExp(r'^[A-Z]{3}$').hasMatch(currency) ||
        status == null ||
        version == null ||
        version < 0 ||
        amount == null ||
        amountPaid == null ||
        remaining == null ||
        (rawDueAt != null && dueAt == null)) {
      _invalidReceivablesResponse();
    }
    return BuyerReceivableProjection(
      id: id,
      clientAccountId: accountId,
      number: number,
      currency: currency,
      amount: amount,
      amountPaid: amountPaid,
      remaining: remaining,
      status: status.toUpperCase(),
      version: version,
      dueAt: dueAt,
    );
  }

  String _requiredAmount(Object? value) {
    final amount = _amountOrNull(value);
    if (amount != null) return amount;
    return _invalidResponse();
  }

  String? _amountOrNull(Object? value) {
    if (value is num && value.isFinite) return value.toString();
    if (value is String && num.tryParse(value)?.isFinite == true) return value;
    return null;
  }

  int? _integer(Object? value) {
    if (value is int) return value;
    if (value is num && value.isFinite && value == value.roundToDouble()) {
      return value.toInt();
    }
    return null;
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

  Never _invalidReceivablesResponse() => throw const NexaApiFailure(
    code: 'invalid_receivables_response',
    userMessage: 'Receivables could not be read.',
  );
}
