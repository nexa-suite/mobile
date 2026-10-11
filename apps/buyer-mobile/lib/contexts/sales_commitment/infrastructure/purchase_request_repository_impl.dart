import '../../../core/network/nexa_api_client.dart';
import '../application/purchase_request_repository.dart';
import '../application/purchase_request_idempotency_store.dart';

final class PurchaseRequestRepositoryImpl implements PurchaseRequestRepository {
  PurchaseRequestRepositoryImpl(this._api, this._idempotencyStore);

  final NexaApiClient _api;
  final PurchaseRequestIdempotencyStore _idempotencyStore;

  static final _uuidPattern = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );
  static const _paymentPreferences = {
    'CREDIT_LINE',
    'BANK_TRANSFER',
    'CARD_STRIPE',
    'CASH',
    'CASH_ON_DELIVERY',
    'WALLET',
  };

  @override
  Future<BuyerPurchaseContextProjection> loadBuyerPurchaseContext() async {
    final account = await _api.get(
      '/client-accounts/me',
      refreshAfterUnauthorized: true,
    );
    final accountId = _uuid(account.body['id']);
    final buyerMembershipId = _uuid(account.body['buyerMembershipId']);
    if (accountId == null || buyerMembershipId == null) _invalidResponse();
    final addresses = await _api.get(
      '/client-accounts/$accountId/addresses',
      refreshAfterUnauthorized: true,
    );
    final values = addresses.data;
    if (values is! List) _invalidResponse();
    final parsed = values.map(_parseAddress).toList(growable: false);
    return BuyerPurchaseContextProjection(
      clientAccountId: accountId,
      buyerMembershipId: buyerMembershipId,
      businessName:
          _string(account.body['commercialName']) ??
          _string(account.body['businessName']) ??
          'Cuenta Buyer',
      paymentCondition: _string(account.body['paymentCondition']) ?? '',
      addresses: parsed,
    );
  }

  @override
  Future<PurchaseRequestDraftPageProjection> listDrafts({
    required int page,
  }) async {
    if (page < 0) _invalidInput();
    final response = await _api.get(
      '/buyer/purchase-request-drafts',
      query: {'page': '$page', 'size': '20'},
      refreshAfterUnauthorized: true,
    );
    final rawItems = response.body['items'];
    final returnedPage = _int(response.body['page']);
    final size = _int(response.body['size']);
    final totalItems = _int(response.body['totalItems']);
    final totalPages = _int(response.body['totalPages']);
    if (rawItems is! List ||
        returnedPage == null ||
        returnedPage != page ||
        size == null ||
        size < 1 ||
        totalItems == null ||
        totalItems < 0 ||
        totalPages == null ||
        totalPages < 0) {
      _invalidResponse();
    }
    final items = rawItems.map(_parseDraftSummary).toList(growable: false);
    if (items.length > size || totalItems < items.length) _invalidResponse();
    if (items.map((draft) => draft.id).toSet().length != items.length) {
      _invalidResponse();
    }
    return PurchaseRequestDraftPageProjection(
      items: items,
      page: returnedPage,
      size: size,
      totalItems: totalItems,
      totalPages: totalPages,
    );
  }

  @override
  Future<PurchaseRequestCreationRecord?> creationRecord(String scopeKey) =>
      _idempotencyStore.creationRecord(scopeKey);

  @override
  Future<void> beginCreation(String scopeKey) =>
      _idempotencyStore.beginCreation(scopeKey);

  @override
  Future<void> recordCreatedDraft(String scopeKey, String draftId) =>
      _idempotencyStore.recordCreatedDraft(scopeKey, draftId);

  @override
  Future<void> clearCreationRecord(String scopeKey) =>
      _idempotencyStore.clearCreationRecord(scopeKey);

  @override
  Future<PurchaseRequestDraftProjection> createDraft({
    required String clientAccountId,
    required String requestedDeliveryDate,
  }) async {
    if (_uuid(clientAccountId) == null ||
        !_isCanonicalDate(requestedDeliveryDate)) {
      _invalidInput();
    }
    final response = await _api.post(
      '/buyer/purchase-request-drafts',
      body: {
        'clientAccountId': clientAccountId,
        'requestedDeliveryDate': requestedDeliveryDate,
      },
    );
    return _parseDraft(response.body, response.headers['etag']);
  }

  @override
  Future<PurchaseRequestDraftProjection> getDraft(String draftId) async {
    if (_uuid(draftId) == null) _invalidInput();
    final response = await _api.get(
      '/buyer/purchase-request-drafts/$draftId',
      refreshAfterUnauthorized: true,
    );
    return _parseDraft(response.body, response.headers['etag']);
  }

  @override
  Future<PurchaseRequestDraftProjection> replaceLine({
    required PurchaseRequestDraftProjection draft,
    required String sellableSkuId,
    required num quantity,
    required String unit,
  }) async {
    if (_uuid(sellableSkuId) == null ||
        quantity <= 0 ||
        !quantity.isFinite ||
        unit.trim().isEmpty ||
        unit.length > 32) {
      _invalidInput();
    }
    final response = await _api.put(
      '/buyer/purchase-request-drafts/${draft.id}/lines',
      ifMatch: draft.etag,
      body: {
        'lines': [
          {
            'skuId': sellableSkuId,
            'quantity': quantity,
            'unit': unit.trim(),
            'notes': '',
          },
        ],
      },
    );
    return _parseDraft(response.body, response.headers['etag']);
  }

  @override
  Future<PurchaseRequestDraftProjection> setDestination({
    required PurchaseRequestDraftProjection draft,
    required String addressId,
  }) async {
    if (_uuid(addressId) == null) _invalidInput();
    final response = await _api.put(
      '/buyer/purchase-request-drafts/${draft.id}/destination',
      ifMatch: draft.etag,
      body: {'addressId': addressId},
    );
    return _parseDraft(response.body, response.headers['etag']);
  }

  @override
  Future<PurchaseRequestDraftProjection> previewRoute(
    PurchaseRequestDraftProjection draft,
  ) async {
    final response = await _api.post(
      '/buyer/purchase-request-drafts/${draft.id}/route-previews',
      ifMatch: draft.etag,
      body: {'provider': 'LOCAL_ESTIMATE'},
    );
    return _parseDraft(response.body, response.headers['etag']);
  }

  @override
  Future<PurchaseRequestDraftProjection> setPreferences({
    required PurchaseRequestDraftProjection draft,
    required String paymentPreference,
    required String requestedDeliveryDate,
  }) async {
    if (!_paymentPreferences.contains(paymentPreference) ||
        !_isCanonicalDate(requestedDeliveryDate)) {
      _invalidInput();
    }
    final response = await _api.put(
      '/buyer/purchase-request-drafts/${draft.id}/preferences',
      ifMatch: draft.etag,
      body: {
        'paymentPreference': paymentPreference,
        'requestedDeliveryDate': requestedDeliveryDate,
      },
    );
    return _parseDraft(response.body, response.headers['etag']);
  }

  @override
  Future<PurchaseRequestReviewProjection> review(String draftId) async {
    if (_uuid(draftId) == null) _invalidInput();
    final response = await _api.get(
      '/buyer/purchase-request-drafts/$draftId/review',
      refreshAfterUnauthorized: true,
    );
    final draft = _map(response.body['draft']);
    final missing = response.body['missing'];
    if (draft == null || missing is! List) _invalidResponse();
    return PurchaseRequestReviewProjection(
      draft: _parseDraft(draft),
      readyToSubmit: response.body['readyToSubmit'] == true,
      missing: missing.whereType<String>().toList(growable: false),
    );
  }

  @override
  Future<PurchaseRequestDraftProjection> submit(
    PurchaseRequestDraftProjection draft, {
    required String scopeKey,
  }) async {
    final idempotencyKey = await _idempotencyStore.keyFor(
      scopeKey: scopeKey,
      draftId: draft.id,
      version: draft.version,
    );
    final response = await _api.post(
      '/buyer/purchase-request-drafts/${draft.id}/submissions',
      ifMatch: draft.etag,
      idempotencyKey: idempotencyKey,
    );
    final submitted = _parseDraft(response.body, response.headers['etag']);
    try {
      await _idempotencyStore.markCompleted(
        scopeKey: scopeKey,
        draftId: draft.id,
        version: draft.version,
      );
    } on Object {
      // Server submission is authoritative; failed local cleanup must not
      // turn a completed request into a client-visible failure.
    }
    return submitted;
  }

  BuyerDeliveryAddressProjection _parseAddress(Object? value) {
    final address = _map(value);
    if (address == null) _invalidResponse();
    final id = _uuid(address['id']);
    if (id == null) _invalidResponse();
    return BuyerDeliveryAddressProjection(
      id: id,
      label: _string(address['label']) ?? 'Dirección registrada',
      line: _string(address['line']) ?? '',
      active: address['active'] != false,
      defaultAddress: address['defaultAddress'] == true,
      latitude: _num(address['latitude']),
      longitude: _num(address['longitude']),
    );
  }

  PurchaseRequestDraftProjection _parseDraft(
    Map<String, Object?> value, [
    String? responseEtag,
  ]) {
    final id = _uuid(value['id']);
    final accountId = _uuid(value['clientAccountId']);
    final version = _int(value['version']);
    final rawLines = value['lines'];
    if (id == null ||
        accountId == null ||
        version == null ||
        rawLines is! List) {
      _invalidResponse();
    }
    final destination = _map(value['destination']);
    final route = _map(value['route']);
    return PurchaseRequestDraftProjection(
      id: id,
      clientAccountId: accountId,
      status: _string(value['status'])?.toUpperCase() ?? 'UNKNOWN',
      version: version,
      etag: responseEtag ?? '"$version"',
      lines: rawLines.map(_parseLine).toList(growable: false),
      requestedDeliveryDate: _string(value['requestedDeliveryDate']),
      paymentPreference: _string(value['paymentPreference']),
      creditResult: _string(value['creditResult']),
      routeProvider: _string(route?['provider']),
      routeEstimated: route?['estimated'] is bool
          ? route!['estimated'] as bool
          : null,
      destinationAddressId: _string(destination?['addressId']),
      hasWarehouseSelection: _map(value['warehouseSelection']) != null,
      submittedAt: _string(value['submittedAt']),
    );
  }

  PurchaseRequestDraftSummaryProjection _parseDraftSummary(Object? value) {
    final draft = _map(value);
    if (draft == null) _invalidResponse();
    final id = _uuid(draft['id']);
    final status = _string(draft['status']);
    final version = _int(draft['version']);
    final lineCount = _int(draft['lineCount']);
    final deliveryDate = _string(draft['requestedDeliveryDate']);
    final createdAt = _dateTime(draft['createdAt']);
    final updatedAt = _dateTime(draft['updatedAt']);
    if (id == null ||
        status == null ||
        version == null ||
        version < 0 ||
        lineCount == null ||
        lineCount < 0 ||
        createdAt == null ||
        updatedAt == null ||
        (deliveryDate != null && DateTime.tryParse(deliveryDate) == null)) {
      _invalidResponse();
    }
    return PurchaseRequestDraftSummaryProjection(
      id: id,
      status: status.toUpperCase(),
      version: version,
      requestedDeliveryDate: deliveryDate,
      lineCount: lineCount,
      createdAt: createdAt,
      updatedAt: updatedAt,
    );
  }

  PurchaseRequestLineProjection _parseLine(Object? value) {
    final line = _map(value);
    if (line == null) _invalidResponse();
    final skuId = _uuid(line['skuId']);
    final quantity = line['quantity'];
    final unit = _string(line['unit']);
    if (skuId == null || quantity is! num || quantity <= 0 || unit == null) {
      _invalidResponse();
    }
    return PurchaseRequestLineProjection(
      skuId: skuId,
      quantity: quantity.toString(),
      unit: unit,
      effectiveUnitPrice: _stringOrNum(line['effectiveUnitPrice']),
      currency: _string(line['currency']),
    );
  }

  String? _uuid(Object? value) {
    final candidate = _string(value);
    if (candidate == null || !_uuidPattern.hasMatch(candidate)) return null;
    return candidate;
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.trim().isNotEmpty ? value.trim() : null;

  String? _stringOrNum(Object? value) =>
      value is num ? value.toString() : _string(value);

  num? _num(Object? value) => value is num ? value : null;

  int? _int(Object? value) => value is int ? value : null;

  DateTime? _dateTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;

  bool _isCanonicalDate(String value) {
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) return false;
    final parsed = DateTime.tryParse(value);
    if (parsed == null) return false;
    final canonical =
        '${parsed.year.toString().padLeft(4, '0')}-'
        '${parsed.month.toString().padLeft(2, '0')}-'
        '${parsed.day.toString().padLeft(2, '0')}';
    return canonical == value;
  }

  Never _invalidInput() => throw const NexaApiFailure(
    code: 'invalid_purchase_request_input',
    userMessage: 'Revisa los datos de la solicitud antes de continuar.',
  );

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_purchase_request_response',
    userMessage: 'La respuesta de la solicitud no se pudo leer.',
  );
}
