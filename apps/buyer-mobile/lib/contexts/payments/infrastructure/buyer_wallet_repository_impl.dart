import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_order_payment_capability_query.dart';
import '../application/buyer_wallet_recharge_intent_repository.dart';
import '../application/buyer_wallet_repository.dart';

final class BuyerWalletRepositoryImpl
    implements
        BuyerWalletRepository,
        BuyerOrderPaymentCapabilityQuery,
        BuyerWalletRechargeIntentRepository {
  BuyerWalletRepositoryImpl(this._api);

  final NexaApiClient _api;

  static final _uuidPattern = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );

  @override
  Future<bool> isOrderPaymentSupported() async {
    final wallet = await readCurrentWallet(page: 0);
    return wallet.state == BuyerWalletState.active &&
        wallet.orderPaymentSupported;
  }

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
    final orderPaymentSupported = _orderPaymentSupported(
      response.body['capabilities'],
    );
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
      orderPaymentSupported: orderPaymentSupported,
    );
  }

  @override
  Future<BuyerWalletRechargeProjection> createRecharge({
    required String amount,
    required String idempotencyKey,
  }) async => (await createRechargeIntent(
    amount: amount,
    idempotencyKey: idempotencyKey,
  )).recharge;

  @override
  Future<BuyerWalletRechargeIntent> createRechargeIntent({
    required String amount,
    required String idempotencyKey,
  }) async {
    if (!_uuidPattern.hasMatch(idempotencyKey) || !_validRechargeAmount(amount)) {
      _invalidRechargeRequest();
    }
    final response = await _api.request(
      'POST',
      '/buyer/wallet/recharges',
      body: {'amount': double.parse(amount)},
      idempotencyKey: idempotencyKey,
      refreshAfterUnauthorized: true,
    );
    final status = _rechargeStatus(response.body['status']);
    if (status == null) _invalidResponse();
    final rawClientSecret = response.body['clientSecret'];
    if (rawClientSecret != null &&
        (rawClientSecret is! String || rawClientSecret.trim().isEmpty)) {
      _invalidResponse();
    }
    if ((status == BuyerWalletRechargeStatus.preparing ||
            status == BuyerWalletRechargeStatus.awaitingPayment) &&
        rawClientSecret == null) {
      _invalidResponse();
    }
    return BuyerWalletRechargeIntent(
      recharge: _parseRecharge(response.body, includeStatusTimes: false),
      clientSecret: rawClientSecret as String?,
    );
  }

  @override
  Future<BuyerWalletRechargeProjection> readRecharge({
    required String rechargeId,
  }) async {
    if (!_uuidPattern.hasMatch(rechargeId)) _invalidRechargeRequest();
    final response = await _api.get(
      '/buyer/wallet/recharges/$rechargeId',
      refreshAfterUnauthorized: true,
    );
    if (response.body.containsKey('clientSecret')) _invalidResponse();
    return _parseRecharge(response.body, includeStatusTimes: true);
  }

  BuyerWalletRechargeProjection _parseRecharge(
    Map<String, Object?> value, {
    required bool includeStatusTimes,
  }) {
    final id = _string(value['id']);
    final status = _rechargeStatus(value['status']);
    final rawAmount = _amount(value['amount']);
    final amount = rawAmount == null ? null : _normalizeRechargeAmount(rawAmount);
    final currency = _string(value['currency']);
    final provider = _string(value['provider']);
    final providerPaymentIntentId = _string(value['providerPaymentIntentId']);
    final createdAt = _dateTime(value['createdAt']);
    final updatedAt = includeStatusTimes ? _dateTime(value['updatedAt']) : null;
    final completedAt = includeStatusTimes && value['completedAt'] != null
        ? _dateTime(value['completedAt'])
        : null;
    if (id == null || !_uuidPattern.hasMatch(id) ||
        status == null || amount == null || !_validRechargeAmount(amount) ||
        currency != 'PEN' || provider == null ||
        providerPaymentIntentId == null || createdAt == null ||
        (includeStatusTimes && updatedAt == null) ||
        (value['completedAt'] != null && completedAt == null)) {
      _invalidResponse();
    }
    return BuyerWalletRechargeProjection(
      id: id,
      status: status,
      amount: amount,
      currency: currency!,
      provider: provider,
      providerPaymentIntentId: providerPaymentIntentId,
      createdAt: createdAt,
      updatedAt: updatedAt,
      completedAt: completedAt,
    );
  }

  BuyerWalletRechargeStatus? _rechargeStatus(Object? value) => switch (value) {
    'PREPARING' => BuyerWalletRechargeStatus.preparing,
    'AWAITING_PAYMENT' => BuyerWalletRechargeStatus.awaitingPayment,
    'SUCCEEDED' => BuyerWalletRechargeStatus.succeeded,
    'FAILED' => BuyerWalletRechargeStatus.failed,
    'CANCELLED' => BuyerWalletRechargeStatus.cancelled,
    'REJECTED' => BuyerWalletRechargeStatus.rejected,
    _ => null,
  };

  bool _validRechargeAmount(String value) {
    if (!RegExp(r'^(?:0|[1-9]\d{0,5})\.\d{2}$').hasMatch(value)) return false;
    final parts = value.split('.');
    final whole = int.tryParse(parts[0]);
    final fraction = int.tryParse(parts[1]);
    if (whole == null || fraction == null) return false;
    final minor = whole * 100 + fraction;
    return minor > 0 && minor <= 99999999;
  }

  String? _normalizeRechargeAmount(String value) {
    if (!RegExp(r'^(?:0|[1-9]\d{0,5})(?:\.\d{1,2})?$').hasMatch(value)) {
      return null;
    }
    final parts = value.split('.');
    final normalized = '${parts[0]}.${(parts.length == 1 ? '' : parts[1]).padRight(2, '0')}';
    return _validRechargeAmount(normalized) ? normalized : null;
  }

  Never _invalidRechargeRequest() => throw const NexaApiFailure(
    code: 'invalid_buyer_wallet_recharge_request',
    userMessage: 'Ingresa un monto válido en PEN para solicitar la recarga.',
  );

  bool _orderPaymentSupported(Object? value) {
    if (value == null) return false;
    if (value is! Map<String, Object?>) _invalidResponse();
    final supported = value['orderPaymentSupported'];
    if (supported == null) return false;
    if (supported is! bool) _invalidResponse();
    return supported;
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
