import '../../../core/network/nexa_api_client.dart';
import '../application/purchase_request_repository.dart';
import '../application/purchase_request_idempotency_store.dart';
import '../application/buyer_purchase_workspace_repository.dart';

final class PurchaseRequestRepositoryImpl
    implements PurchaseRequestRepository, BuyerPurchaseWorkspaceRepository {
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
    final credentials = _api.sessionCredentials;
    final epoch = credentials?.authorityEpoch;
    final fingerprint = credentials?.authorityFingerprint;
    void requireCurrentScope() {
      if (credentials == null ||
          !identical(credentials, _api.sessionCredentials) ||
          credentials.accessToken == null ||
          credentials.authorityEpoch != epoch ||
          credentials.authorityFingerprint != fingerprint) {
        throw const NexaApiFailure(
          code: 'buyer_purchase_scope_changed',
          userMessage: 'Tu acceso cambió. Abre nuevamente la solicitud.',
        );
      }
    }

    requireCurrentScope();
    final account = await _api.get(
      '/client-accounts/me',
      refreshAfterUnauthorized: true,
    );
    requireCurrentScope();
    final accountId = _uuid(account.body['id']);
    if (accountId == null) {
      _invalidResponse();
    }
    final addresses = await _api.get(
      '/client-accounts/$accountId/addresses',
      refreshAfterUnauthorized: true,
    );
    requireCurrentScope();
    final values = addresses.data;
    if (values is! List) {
      _invalidResponse();
    }
    final parsed = values.map(_parseAddress).toList(growable: false);
    return BuyerPurchaseContextProjection(
      clientAccountId: accountId,
      businessName:
          _string(account.body['commercialName']) ??
          _string(account.body['businessName']) ??
          'Cuenta Buyer',
      paymentCondition: _string(account.body['paymentCondition']) ?? '',
      addresses: parsed,
    );
  }

  @override
  Future<BuyerDeliveryLeadPolicyProjection> loadDeliveryLeadPolicy() async {
    final response = await _api.get(
      '/buyer/delivery-lead-policy',
      refreshAfterUnauthorized: true,
    );
    final calendarDays = _int(response.body['minimumDeliveryLeadDays']);
    final minimumDate = _canonicalDateValue(
      response.body['earliestAllowedDeliveryDate'],
    );
    final timezone = _string(response.body['timezone']);
    if (response.body['dayUnit'] != 'CALENDAR' ||
        calendarDays == null ||
        calendarDays < 0 ||
        minimumDate == null ||
        timezone == null) {
      _invalidResponse();
    }
    return BuyerDeliveryLeadPolicyProjection(
      minimumDeliveryLeadDays: calendarDays,
      earliestAllowedDeliveryDate: minimumDate,
      timezone: timezone,
    );
  }

  @override
  Future<PurchaseRequestDraftPageProjection> listDrafts({
    required int page,
  }) async {
    if (page < 0) {
      _invalidInput();
    }
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
    if (items.length > size || totalItems < items.length) {
      _invalidResponse();
    }
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
    if (_uuid(draftId) == null) {
      _invalidInput();
    }
    final response = await _api.get(
      '/buyer/purchase-request-drafts/$draftId',
      refreshAfterUnauthorized: true,
    );
    final result = _parseDraft(response.body, response.headers['etag']);
    if (result.id != draftId) {
      _invalidResponse();
    }
    return result;
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
    if (_uuid(addressId) == null) {
      _invalidInput();
    }
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
    if (_uuid(draftId) == null) {
      _invalidInput();
    }
    final response = await _api.get(
      '/buyer/purchase-request-drafts/$draftId/review',
      refreshAfterUnauthorized: true,
    );
    final draft = _map(response.body['draft']);
    final missing = response.body['missing'];
    if (draft == null ||
        missing is! List ||
        missing.any((value) => value is! String) ||
        response.body['readyToSubmit'] is! bool) {
      _invalidResponse();
    }
    final result = _parseDraft(draft);
    if (result.id != draftId ||
        (response.body['readyToSubmit'] == true &&
            (result.status != 'READY_TO_SUBMIT' || missing.isNotEmpty))) {
      _invalidResponse();
    }
    return PurchaseRequestReviewProjection(
      draft: result,
      readyToSubmit: response.body['readyToSubmit'] == true,
      missing: missing.whereType<String>().toList(growable: false),
    );
  }

  @override
  Future<PurchaseRequestDraftProjection> submit(
    PurchaseRequestDraftProjection draft, {
    required String scopeKey,
  }) async {
    final authority = _api.sessionCredentials?.authorityFingerprint;
    final authorityEpoch = _api.sessionCredentials?.authorityEpoch;
    final idempotencyKey = await _idempotencyStore.keyFor(
      scopeKey: scopeKey,
      draftId: draft.id,
      version: draft.version,
    );
    if (_api.sessionCredentials?.authorityFingerprint != authority ||
        _api.sessionCredentials?.authorityEpoch != authorityEpoch) {
      throw const NexaApiFailure(
        code: 'buyer_authority_changed',
        userMessage: 'El acceso cambió. Consulta nuevamente el borrador.',
      );
    }
    final response = await _api.post(
      '/buyer/purchase-request-drafts/${draft.id}/submissions',
      ifMatch: draft.etag,
      idempotencyKey: idempotencyKey,
    );
    final submitted = _parseDraft(response.body, response.headers['etag']);
    if (submitted.id != draft.id ||
        submitted.clientAccountId != draft.clientAccountId ||
        submitted.status != 'SUBMITTED') {
      _invalidResponse();
    }
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

  @override
  Future<BuyerRequestPage> listRequests({
    required int page,
    String? status,
  }) async {
    if (page < 0) {
      _invalidInput();
    }
    final response = await _api.get(
      '/purchase-requests',
      query: {
        'page': '$page',
        'size': '20',
        'sort': 'createdAt,desc',
        'status': ?status,
      },
      refreshAfterUnauthorized: true,
    );
    final values = response.body['items'];
    final returnedPage = _int(response.body['page']);
    final size = _int(response.body['size']);
    final total = _int(response.body['total']);
    if (values is! List ||
        returnedPage != page ||
        size == null ||
        size < 1 ||
        total == null ||
        total < 0 ||
        values.length > size ||
        values.length > total) {
      _invalidResponse();
    }
    final items = values
        .map((value) => _parseRequest(value))
        .toList(growable: false);
    if (items.map((item) => item.id).toSet().length != items.length) {
      _invalidResponse();
    }
    return BuyerRequestPage(
      items: List.unmodifiable(items),
      page: page,
      size: size,
      total: total,
    );
  }

  @override
  Future<BuyerRequestProjection> request(String id) async {
    if (_uuid(id) == null) {
      _invalidInput();
    }
    final response = await _api.get(
      '/purchase-requests/$id',
      refreshAfterUnauthorized: true,
    );
    final result = _parseRequest(
      response.body,
      etag: response.headers['etag'],
      requireLines: true,
    );
    if (result.id != id) {
      _invalidResponse();
    }
    return result;
  }

  @override
  Future<List<BuyerRequestEvent>> events(String id) async {
    if (_uuid(id) == null) {
      _invalidInput();
    }
    final response = await _api.get(
      '/purchase-requests/$id/events',
      refreshAfterUnauthorized: true,
    );
    if (response.data is! List) {
      _invalidResponse();
    }
    return List.unmodifiable(
      (response.data as List).map((raw) {
        final value = _map(raw);
        if (value == null ||
            _uuid(value['eventId']) == null ||
            _string(value['eventType']) == null ||
            _dateTime(value['occurredAt']) == null) {
          _invalidResponse();
        }
        return BuyerRequestEvent(
          id: value['eventId'] as String,
          type: value['eventType'] as String,
          fromStatus: _string(value['fromStatus']),
          toStatus: _string(value['toStatus']),
          occurredAt: _dateTime(value['occurredAt']),
        );
      }),
    );
  }

  @override
  Future<BuyerMaterialChange?> currentChange(String id) async {
    if (_uuid(id) == null) {
      _invalidInput();
    }
    final response = await _api.get(
      '/purchase-requests/$id/material-changes/current',
      refreshAfterUnauthorized: true,
    );
    final value = response.body;
    final changeId = _uuid(value['id']);
    if (changeId == null ||
        _string(value['purchaseRequestId']) != id ||
        _int(value['requestVersion']) == null ||
        (value['requestVersion'] as int) < 0 ||
        _string(value['status']) != 'PROPOSED' ||
        _string(value['reason']) == null ||
        _map(value['originalTerms']) == null ||
        _map(value['proposedTerms']) == null) {
      _invalidResponse();
    }
    return BuyerMaterialChange(
      id: changeId,
      status: _string(value['status']) ?? 'UNKNOWN',
      reason: _string(value['reason']) ?? '',
      requestVersion: value['requestVersion'] as int,
      original: _parseTerms(_map(value['originalTerms'])!, requireLines: true),
      proposed: _parseTerms(_map(value['proposedTerms'])!, requireLines: true),
    );
  }

  @override
  Future<BuyerRequestProjection> decide({
    required BuyerRequestProjection request,
    required BuyerRequestDecision decision,
    required String scopeKey,
    BuyerMaterialChange? proposal,
  }) async {
    if (_uuid(request.id) == null ||
        scopeKey.trim().isEmpty ||
        request.version < 0 ||
        request.etag != '"${request.version}"') {
      _invalidInput();
    }
    final authority = _api.sessionCredentials?.authorityFingerprint;
    final authorityEpoch = _api.sessionCredentials?.authorityEpoch;
    final path = switch (decision) {
      BuyerRequestDecision.withdraw => 'withdrawals',
      BuyerRequestDecision.acceptChange =>
        'material-changes/${proposal?.id}/acceptances',
      BuyerRequestDecision.rejectChange =>
        'material-changes/${proposal?.id}/rejections',
    };
    if (decision != BuyerRequestDecision.withdraw &&
        (proposal == null ||
            _uuid(proposal.id) == null ||
            proposal.requestVersion != request.version ||
            proposal.status != 'PROPOSED')) {
      _invalidInput();
    }
    final key = await _idempotencyStore.keyFor(
      scopeKey: '$scopeKey|$path',
      draftId: request.id,
      version: request.version,
    );
    if (_api.sessionCredentials?.authorityFingerprint != authority ||
        _api.sessionCredentials?.authorityEpoch != authorityEpoch) {
      throw const NexaApiFailure(
        code: 'buyer_authority_changed',
        userMessage: 'El acceso cambió. Consulta nuevamente la solicitud.',
      );
    }
    final response = await _api.post(
      '/purchase-requests/${request.id}/$path',
      ifMatch: request.etag,
      idempotencyKey: key,
    );
    final result = _parseRequest(
      response.body,
      etag: response.headers['etag'],
      requireLines: true,
    );
    if (result.id != request.id) {
      _invalidResponse();
    }
    return result;
  }

  @override
  Future<PurchaseRequestDraftProjection> replaceDraftLines({
    required PurchaseRequestDraftProjection draft,
    required List<PurchaseRequestLineProjection> lines,
  }) async {
    if (_uuid(draft.id) == null ||
        draft.version < 0 ||
        draft.etag != '"${draft.version}"' ||
        lines.isEmpty ||
        lines.length > 100 ||
        lines.map((line) => line.skuId).toSet().length != lines.length) {
      _invalidInput();
    }
    for (final line in lines) {
      final quantity = num.tryParse(line.quantity);
      if (_uuid(line.skuId) == null ||
          quantity == null ||
          !quantity.isFinite ||
          quantity <= 0 ||
          line.unit.trim().isEmpty ||
          line.unit.length > 32) {
        _invalidInput();
      }
    }
    final response = await _api.put(
      '/buyer/purchase-request-drafts/${draft.id}/lines',
      ifMatch: draft.etag,
      body: {
        'lines': [
          for (final line in lines)
            {
              'skuId': line.skuId,
              'quantity': num.parse(line.quantity),
              'unit': line.unit,
              'notes': line.notes ?? '',
            },
        ],
      },
    );
    final result = _parseDraft(response.body, response.headers['etag']);
    if (result.id != draft.id ||
        result.clientAccountId != draft.clientAccountId) {
      _invalidResponse();
    }
    return result;
  }

  BuyerRequestProjection _parseRequest(
    Object? raw, {
    String? etag,
    bool requireLines = false,
  }) {
    final value = _map(raw);
    if (value == null) {
      _invalidResponse();
    }
    final id = _uuid(value['id']);
    final code = _string(value['code']);
    final status = _string(value['status']);
    final version = _int(value['version']);
    if (id == null ||
        code == null ||
        status == null ||
        version == null ||
        version < 0 ||
        (etag != null && etag != '"$version"') ||
        (value['lineCount'] != null &&
            (_int(value['lineCount']) == null ||
                (value['lineCount'] as int) < 0)) ||
        (requireLines && value['lines'] is! List)) {
      _invalidResponse();
    }
    final terms = _parseTerms(value, requireLines: requireLines);
    return BuyerRequestProjection(
      id: id,
      code: code,
      status: status,
      version: version,
      etag: etag ?? '"$version"',
      lineCount: _int(value['lineCount']) ?? terms.lines.length,
      terms: terms,
      reviewNote: _string(value['reviewNote']),
      expiresAt: _string(value['expiresAt']),
    );
  }

  BuyerRequestTerms _parseTerms(
    Map<String, Object?> value, {
    bool requireLines = false,
  }) {
    final rawLines = value['lines'];
    if ((requireLines && rawLines is! List) ||
        (rawLines != null && rawLines is! List)) {
      _invalidResponse();
    }
    final lines = (rawLines as List? ?? const [])
        .map((raw) {
          final line = _map(raw);
          if (line == null ||
              _string(line['itemName']) == null ||
              _num(line['quantity']) == null ||
              _string(line['unit']) == null ||
              !(line['quantity'] as num).isFinite ||
              (line['quantity'] as num) <= 0 ||
              (line['unitPriceAmount'] != null &&
                  (_num(line['unitPriceAmount']) == null ||
                      !(line['unitPriceAmount'] as num).isFinite ||
                      (line['unitPriceAmount'] as num) < 0 ||
                      _string(line['unitPriceCurrency']) == null))) {
            _invalidResponse();
          }
          return BuyerRequestLine(
            name: line['itemName'] as String,
            quantity: line['quantity'] as num,
            unit: line['unit'] as String,
            presentation: _string(line['presentation']),
            price: _num(line['unitPriceAmount']),
            currency: _string(line['unitPriceCurrency']),
            notes: _string(line['notes']),
          );
        })
        .toList(growable: false);
    return BuyerRequestTerms(
      priority: _string(value['priority']),
      deliveryDate: _string(value['requestedDeliveryDate']),
      paymentOption: _string(value['paymentOption']),
      comment: _string(value['comment']),
      deliveryProfile: _string(value['deliveryProfileSnapshot']),
      lines: List.unmodifiable(lines),
    );
  }

  BuyerDeliveryAddressProjection _parseAddress(Object? value) {
    final address = _map(value);
    if (address == null) {
      _invalidResponse();
    }
    final id = _uuid(address['id']);
    final label = _string(address['label']);
    final line = _string(address['line']);
    final active = address['active'];
    final defaultAddress = address['defaultAddress'];
    if (id == null ||
        label == null ||
        line == null ||
        active is! bool ||
        defaultAddress is! bool) {
      _invalidResponse();
    }
    return BuyerDeliveryAddressProjection(
      id: id,
      label: label,
      line: line,
      active: active,
      defaultAddress: defaultAddress,
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
        version < 0 ||
        (responseEtag != null && responseEtag != '"$version"') ||
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
    if (draft == null) {
      _invalidResponse();
    }
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
    if (line == null) {
      _invalidResponse();
    }
    final skuId = _uuid(line['skuId']);
    final quantity = line['quantity'];
    final unit = _string(line['unit']);
    if (skuId == null ||
        quantity is! num ||
        !quantity.isFinite ||
        quantity <= 0 ||
        unit == null) {
      _invalidResponse();
    }
    return PurchaseRequestLineProjection(
      skuId: skuId,
      quantity: quantity.toString(),
      unit: unit,
      effectiveUnitPrice: _stringOrNum(line['effectiveUnitPrice']),
      currency: _string(line['currency']),
      skuCode: _string(line['skuCode']),
      presentation: _string(line['presentation']),
      notes: _string(line['notes']),
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

  DateTime? _canonicalDateValue(Object? value) {
    if (value is! String || !_isCanonicalDate(value)) return null;
    final parts = value.split('-').map(int.parse).toList(growable: false);
    return DateTime(parts[0], parts[1], parts[2]);
  }

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
