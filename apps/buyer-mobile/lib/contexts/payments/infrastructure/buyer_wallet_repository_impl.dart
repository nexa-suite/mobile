import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_wallet_repository.dart';

final class BuyerWalletRepositoryImpl implements BuyerWalletRepository {
  BuyerWalletRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<BuyerWalletProjection> readCurrentWallet({required int page}) async {
    if (page < 0) _invalidResponse();
    final response = await _api.get(
      '/buyer/wallet',
      query: {'page': '$page', 'size': '25'},
      refreshAfterUnauthorized: true,
    );
    final stateValue = _string(response.body['status']);
    final state = switch (stateValue) {
      'ACTIVE' => BuyerWalletState.active,
      'NOT_INITIALIZED' => BuyerWalletState.notInitialized,
      _ => _invalidResponse(),
    };
    final currency = _string(response.body['currency']);
    final postedBalance = _amount(response.body['postedBalance']);
    final reservedBalance = _amount(response.body['reservedBalance']);
    final availableBalance = _amount(response.body['availableBalance']);
    if (currency != 'PEN') _invalidResponse();
    if (state == BuyerWalletState.active &&
        (postedBalance == null ||
            reservedBalance == null ||
            availableBalance == null)) {
      _invalidResponse();
    }
    if (state == BuyerWalletState.notInitialized &&
        (response.body['postedBalance'] != null ||
            response.body['reservedBalance'] != null ||
            response.body['availableBalance'] != null)) {
      _invalidResponse();
    }

    final rawMovements = response.body['movements'];
    if (rawMovements is! Map<String, Object?>) _invalidResponse();
    final rawItems = rawMovements['items'];
    final returnedPage = _integer(rawMovements['page']);
    final size = _integer(rawMovements['size']);
    final total = _integer(rawMovements['total']);
    if (rawItems is! List ||
        returnedPage != page ||
        size == null ||
        size < 1 ||
        size > 100 ||
        total == null ||
        total < 0) {
      _invalidResponse();
    }
    final items = rawItems.map(_parseMovement).toList(growable: false);
    if (items.length > size || total < items.length) _invalidResponse();
    return BuyerWalletProjection(
      state: state,
      currency: currency!,
      postedBalance: postedBalance,
      reservedBalance: reservedBalance,
      availableBalance: availableBalance,
      movements: BuyerWalletMovementsPageProjection(
        items: items,
        page: returnedPage!,
        size: size,
        total: total,
      ),
    );
  }

  BuyerWalletMovementProjection _parseMovement(Object? value) {
    if (value is! Map<String, Object?>) _invalidResponse();
    final type = _string(value['type']);
    final amountDelta = _amount(value['amountDelta'], allowNegative: true);
    final occurredAt = _dateTime(value['occurredAt']);
    if (type == null || amountDelta == null || occurredAt == null) {
      _invalidResponse();
    }
    return BuyerWalletMovementProjection(
      type: type,
      amountDelta: amountDelta,
      occurredAt: occurredAt,
    );
  }

  String? _string(Object? value) =>
      value is String && value.trim().isNotEmpty ? value.trim() : null;

  String? _amount(Object? value, {bool allowNegative = false}) {
    final raw = switch (value) {
      num amount when amount.isFinite => amount.toString(),
      String amount when amount.trim() == amount && amount.isNotEmpty => amount,
      _ => null,
    };
    if (raw == null || !RegExp(r'^-?\d+(?:\.\d+)?$').hasMatch(raw)) {
      return null;
    }
    final parsed = num.tryParse(raw);
    if (parsed == null || !parsed.isFinite || (!allowNegative && parsed < 0)) {
      return null;
    }
    return raw;
  }

  int? _integer(Object? value) {
    if (value is int) return value;
    if (value is num && value.isFinite && value == value.roundToDouble()) {
      return value.toInt();
    }
    return null;
  }

  DateTime? _dateTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_buyer_wallet_response',
    userMessage: 'La billetera no pudo leerse de forma segura.',
  );
}
