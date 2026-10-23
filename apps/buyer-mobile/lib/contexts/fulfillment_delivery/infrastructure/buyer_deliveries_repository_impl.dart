import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

import '../application/buyer_deliveries_repository.dart';

final class BuyerDeliveriesRepositoryImpl implements BuyerDeliveriesRepository {
  BuyerDeliveriesRepositoryImpl(this._api);

  static const pageSize = 25;
  static const _evidencePageSize = 100;
  static const _maxEvidencePages = 100;

  final NexaApiClient _api;

  @override
  Future<BuyerDeliveryPageProjection> list({required int page}) async {
    if (page < 0) _invalidResponse();
    final response = await _api.get(
      '/buyer/deliveries',
      query: {'page': '$page', 'size': '$pageSize'},
      refreshAfterUnauthorized: true,
    );
    final rawItems = response.body['items'];
    final responsePage = _int(response.body['page']);
    final size = _int(response.body['size']);
    final total = _int(response.body['total']);
    if (rawItems is! List ||
        responsePage != page ||
        size != pageSize ||
        total == null ||
        total < 0) {
      _invalidResponse();
    }
    final items = rawItems.map(_parseDelivery).toList(growable: false);
    if (items.length > pageSize || total < items.length) _invalidResponse();
    if (items.map((item) => item.id).toSet().length != items.length) {
      _invalidResponse();
    }
    return BuyerDeliveryPageProjection(
      items: items,
      page: responsePage!,
      size: size!,
      total: total,
    );
  }

  @override
  Future<BuyerDeliveryDetailProjection> detail(String deliveryId) async {
    if (!_isUuid(deliveryId)) _invalidResponse();
    final encodedId = Uri.encodeComponent(deliveryId);
    final responses = await Future.wait([
      _api.get('/buyer/deliveries/$encodedId', refreshAfterUnauthorized: true),
      _api.get(
        '/buyer/deliveries/$encodedId/events',
        refreshAfterUnauthorized: true,
      ),
      _api.get(
        '/buyer/deliveries/$encodedId/buyer-receipts',
        refreshAfterUnauthorized: true,
      ),
    ]);
    final delivery = _parseDelivery(responses[0].body);
    final rawEvents = responses[1].data;
    final rawReceipts = responses[2].data;
    if (delivery.id != deliveryId ||
        rawEvents is! List ||
        rawReceipts is! List) {
      _invalidResponse();
    }
    final events = rawEvents.map(_parseEvent).toList(growable: false);
    final receiptRecords = rawReceipts
        .map((value) => _parseReceipt(value, deliveryId))
        .toList(growable: false);
    if (receiptRecords.map((receipt) => receipt.id).toSet().length !=
        receiptRecords.length) {
      _invalidResponse();
    }
    final receipts = await Future.wait(
      receiptRecords.map((receipt) async {
        if (receipt.decision != 'DISPUTED') return receipt;
        final evidence = await listReceiptEvidence(receipt.id);
        return receipt.withEvidence(evidence);
      }),
    );
    return BuyerDeliveryDetailProjection(
      delivery: delivery,
      events: events,
      receipts: receipts,
    );
  }

  @override
  Future<List<BuyerReceiptEvidenceProjection>> listReceiptEvidence(
    String receiptId,
  ) async {
    if (!_isUuid(receiptId)) _invalidRequest('buyer_receipt_id_invalid');
    final result = <BuyerReceiptEvidenceProjection>[];
    final seen = <String>{};
    var expectedTotal = 0;
    for (var page = 0; page < _maxEvidencePages; page++) {
      final response = await _api.get(
        '/business-document-evidence',
        query: {
          'subjectType': 'BUYER_RECEIPT',
          'subjectId': receiptId,
          'page': '$page',
          'size': '$_evidencePageSize',
        },
        refreshAfterUnauthorized: true,
      );
      final rawItems = response.body['items'];
      final responsePage = _int(response.body['page']);
      final size = _int(response.body['size']);
      final total = _int(response.body['total']);
      if (rawItems is! List ||
          responsePage != page ||
          size != _evidencePageSize ||
          total == null ||
          total < 0 ||
          rawItems.length > _evidencePageSize ||
          total < rawItems.length) {
        _invalidResponse();
      }
      expectedTotal = total;
      final records = rawItems
          .map((value) => _parseEvidence(value, receiptId))
          .toList(growable: false);
      for (final record in records) {
        if (seen.add(record.id)) result.add(record);
      }
      if (result.length >= expectedTotal) {
        return List.unmodifiable(result);
      }
      if (records.isEmpty || records.length < _evidencePageSize) {
        _invalidResponse();
      }
    }
    _invalidResponse();
  }

  @override
  Future<BuyerReceiptEvidenceProjection> uploadReceiptEvidence({
    required String receiptId,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
  }) async {
    final normalizedName = filename.trim();
    final normalizedType = contentType.trim().toLowerCase();
    final extension = normalizedName.split('.').last.toLowerCase();
    final allowedTypes = const {
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'png': 'image/png',
      'pdf': 'application/pdf',
    };
    if (!_isUuid(receiptId) ||
        !_isUuid(idempotencyKey) ||
        normalizedName.isEmpty ||
        normalizedName.length > 255 ||
        normalizedName.contains('/') ||
        normalizedName.contains('\\') ||
        normalizedName.contains('\n') ||
        normalizedName.contains('\r') ||
        allowedTypes[extension] != normalizedType ||
        bytes.isEmpty ||
        bytes.length > NexaApiClient.maxEvidenceUploadBytes) {
      _invalidRequest('buyer_receipt_evidence_invalid');
    }
    final response = await _api.postMultipart(
      path: '/business-document-evidence',
      fields: {'subjectType': 'BUYER_RECEIPT', 'subjectId': receiptId},
      fieldName: 'file',
      filename: normalizedName,
      contentType: normalizedType,
      bytes: bytes,
      idempotencyKey: idempotencyKey,
      refreshAfterUnauthorized: true,
    );
    return _parseEvidence(response.body, receiptId);
  }

  @override
  Future<BuyerDeliveryTrackingProjection?> liveLocation(
    String deliveryId,
  ) async {
    if (!_isUuid(deliveryId)) _invalidResponse();
    final response = await _api.get(
      '/buyer/deliveries/${Uri.encodeComponent(deliveryId)}/live-location',
      refreshAfterUnauthorized: true,
    );
    final body = _map(response.body);
    if (body == null) _invalidResponse();
    final responseDeliveryId = _string(body['deliveryId']);
    if (responseDeliveryId != deliveryId) _invalidResponse();
    final rawLocation = body['location'];
    if (rawLocation == null) return null;
    final location = _map(rawLocation);
    if (location == null) _invalidResponse();
    final sampleId = _string(location['sampleId']);
    final latitude = _double(location['latitude']);
    final longitude = _double(location['longitude']);
    final accuracyMeters = _double(location['accuracyMeters']);
    final capturedAt = _dateTime(location['capturedAt']);
    final expiresAt = _dateTime(location['expiresAt']);
    if (sampleId == null ||
        !_isUuid(sampleId) ||
        latitude == null ||
        !latitude.isFinite ||
        latitude < -90 ||
        latitude > 90 ||
        longitude == null ||
        !longitude.isFinite ||
        longitude < -180 ||
        longitude > 180 ||
        accuracyMeters == null ||
        !accuracyMeters.isFinite ||
        accuracyMeters < 0 ||
        capturedAt == null ||
        expiresAt == null ||
        !expiresAt.isAfter(capturedAt)) {
      _invalidResponse();
    }
    return BuyerDeliveryTrackingProjection(
      deliveryId: responseDeliveryId!,
      sampleId: sampleId,
      latitude: latitude,
      longitude: longitude,
      accuracyMeters: accuracyMeters,
      capturedAt: capturedAt,
      expiresAt: expiresAt,
    );
  }

  @override
  Future<BuyerHandoffValidationProjection> validateHandoff(String token) async {
    final normalizedToken = token.trim();
    if (normalizedToken.isEmpty || normalizedToken.length > 400) {
      _invalidRequest('handoff_token_invalid');
    }
    final response = await _api.request(
      'POST',
      '/delivery-handoff/validations',
      body: {'token': normalizedToken},
      refreshAfterUnauthorized: true,
    );
    final body = _map(response.body);
    if (body == null) _invalidResponse();
    final handoffId = _string(body['handoffId']);
    final deliveryId = _string(body['deliveryId']);
    final attemptId = _string(body['attemptId']);
    final expiresAt = _dateTime(body['expiresAt']);
    final deliveryStatus = _string(body['deliveryStatus']);
    final deliveredQuantity = _num(body['deliveredQuantity']);
    if (handoffId == null ||
        !_isUuid(handoffId) ||
        deliveryId == null ||
        !_isUuid(deliveryId) ||
        attemptId == null ||
        !_isUuid(attemptId) ||
        expiresAt == null ||
        deliveryStatus == null ||
        deliveredQuantity == null ||
        !deliveredQuantity.isFinite ||
        deliveredQuantity < 0) {
      _invalidResponse();
    }
    return BuyerHandoffValidationProjection(
      handoffId: handoffId,
      deliveryId: deliveryId,
      attemptId: attemptId,
      expiresAt: expiresAt,
      deliveryStatus: deliveryStatus,
      deliveredQuantity: deliveredQuantity,
    );
  }

  @override
  Future<BuyerReceiptProjection> recordReceipt({
    required String deliveryId,
    required String token,
    required String decision,
    required num acceptedQuantity,
    required String? reason,
    required String idempotencyKey,
  }) async {
    final normalizedDecision = decision.trim().toUpperCase();
    final normalizedToken = token.trim();
    final normalizedReason = reason?.trim();
    if (!_isUuid(deliveryId) ||
        !_isUuid(idempotencyKey) ||
        normalizedToken.isEmpty ||
        normalizedToken.length > 400 ||
        !{'ACCEPTED', 'DISPUTED'}.contains(normalizedDecision) ||
        !acceptedQuantity.isFinite ||
        acceptedQuantity < 0 ||
        (normalizedReason != null && normalizedReason.length > 2000) ||
        (normalizedDecision == 'DISPUTED' &&
            (normalizedReason == null || normalizedReason.isEmpty))) {
      _invalidRequest('buyer_receipt_request_invalid');
    }
    final response = await _api.request(
      'POST',
      '/deliveries/${Uri.encodeComponent(deliveryId)}/buyer-receipts',
      body: {
        'token': normalizedToken,
        'decision': normalizedDecision,
        'acceptedQuantity': acceptedQuantity,
        'reason': normalizedReason,
      },
      idempotencyKey: idempotencyKey,
      refreshAfterUnauthorized: true,
    );
    final body = _map(response.body);
    if (body == null) _invalidResponse();
    final id = _string(body['id']);
    final responseDeliveryId = _string(body['deliveryId']);
    final attemptId = _string(body['attemptId']);
    final responseDecision = _string(body['decision']);
    final driverDeliveredQuantity = _num(body['driverDeliveredQuantity']);
    final responseAcceptedQuantity = _num(body['acceptedQuantity']);
    final occurredAt = _dateTime(body['occurredAt']);
    final replayed = body['replayed'];
    final responseReason = _optionalString(body, 'reason');
    final recordedBy = _optionalString(body, 'recordedByMembershipId');
    if (id == null ||
        !_isUuid(id) ||
        responseDeliveryId != deliveryId ||
        attemptId == null ||
        !_isUuid(attemptId) ||
        responseDecision != normalizedDecision ||
        driverDeliveredQuantity == null ||
        !driverDeliveredQuantity.isFinite ||
        driverDeliveredQuantity < 0 ||
        responseAcceptedQuantity == null ||
        !responseAcceptedQuantity.isFinite ||
        responseAcceptedQuantity < 0 ||
        responseAcceptedQuantity > driverDeliveredQuantity ||
        (normalizedDecision == 'ACCEPTED' &&
            responseAcceptedQuantity != driverDeliveredQuantity) ||
        occurredAt == null ||
        replayed is! bool ||
        (normalizedDecision == 'DISPUTED' &&
            (responseReason == null || responseReason.isEmpty))) {
      _invalidResponse();
    }
    return BuyerReceiptProjection(
      id: id,
      deliveryId: responseDeliveryId!,
      attemptId: attemptId,
      decision: responseDecision!,
      driverDeliveredQuantity: driverDeliveredQuantity,
      acceptedQuantity: responseAcceptedQuantity,
      reason: responseReason,
      occurredAt: occurredAt,
      replayed: replayed,
      recordedByMembershipId: recordedBy,
    );
  }

  BuyerReceiptProjection _parseReceipt(Object? value, String deliveryId) {
    final receipt = _map(value);
    if (receipt == null) _invalidResponse();
    final id = _string(receipt['id']);
    final responseDeliveryId = _string(receipt['deliveryId']);
    final attemptId = _string(receipt['attemptId']);
    final decision = _string(receipt['decision']);
    final driverQuantity = _num(receipt['driverDeliveredQuantity']);
    final acceptedQuantity = _num(receipt['acceptedQuantity']);
    final occurredAt = _dateTime(receipt['occurredAt']);
    final recordedBy = _string(receipt['recordedByMembershipId']);
    final reason = _optionalString(receipt, 'reason');
    if (id == null ||
        !_isUuid(id) ||
        responseDeliveryId != deliveryId ||
        attemptId == null ||
        !_isUuid(attemptId) ||
        !{'ACCEPTED', 'DISPUTED'}.contains(decision) ||
        driverQuantity == null ||
        !driverQuantity.isFinite ||
        driverQuantity < 0 ||
        acceptedQuantity == null ||
        !acceptedQuantity.isFinite ||
        acceptedQuantity < 0 ||
        acceptedQuantity > driverQuantity ||
        (decision == 'ACCEPTED' && acceptedQuantity != driverQuantity) ||
        occurredAt == null ||
        recordedBy == null ||
        !_isUuid(recordedBy) ||
        (decision == 'DISPUTED' && (reason == null || reason.isEmpty))) {
      _invalidResponse();
    }
    return BuyerReceiptProjection(
      id: id,
      deliveryId: responseDeliveryId!,
      attemptId: attemptId,
      decision: decision!,
      driverDeliveredQuantity: driverQuantity,
      acceptedQuantity: acceptedQuantity,
      reason: reason,
      occurredAt: occurredAt,
      recordedByMembershipId: recordedBy,
    );
  }

  BuyerReceiptEvidenceProjection _parseEvidence(
    Object? value,
    String receiptId,
  ) {
    final evidence = _map(value);
    if (evidence == null) _invalidResponse();
    final id = _string(evidence['id']);
    final subjectType = _string(evidence['subjectType']);
    final subjectId = _string(evidence['subjectId']);
    final lifecycleStatus = _string(evidence['lifecycleStatus']);
    final originalFilename = _string(evidence['originalFilename']);
    final contentType = _string(evidence['declaredContentType']);
    final byteSize = _int(evidence['byteSize']);
    final createdAt = _dateTime(evidence['createdAt']);
    final scannedAt = _optionalDateTime(evidence, 'scannedAt');
    final failureCode = _optionalString(evidence, 'failureCode');
    final requestedBy = _string(evidence['requestedByMembershipId']);
    const lifecycleStatuses = {
      'REQUESTED',
      'UPLOADING',
      'QUARANTINED',
      'SCANNING',
      'AVAILABLE',
      'REJECTED',
      'DELETED',
    };
    if (id == null ||
        !_isUuid(id) ||
        subjectType != 'BUYER_RECEIPT' ||
        subjectId != receiptId ||
        !lifecycleStatuses.contains(lifecycleStatus) ||
        originalFilename == null ||
        contentType == null ||
        byteSize == null ||
        byteSize < 0 ||
        createdAt == null ||
        requestedBy == null ||
        !_isUuid(requestedBy)) {
      _invalidResponse();
    }
    return BuyerReceiptEvidenceProjection(
      id: id,
      receiptId: receiptId,
      lifecycleStatus: lifecycleStatus!,
      originalFilename: originalFilename,
      contentType: contentType.toLowerCase(),
      byteSize: byteSize,
      createdAt: createdAt,
      scannedAt: scannedAt,
      failureCode: failureCode,
      requestedByMembershipId: requestedBy,
    );
  }

  BuyerDeliveryProjection _parseDelivery(Object? value) {
    final delivery = _map(value);
    if (delivery == null) _invalidResponse();
    final id = _string(delivery['id']);
    final salesOrderNumber = _string(delivery['salesOrderNumber']);
    final status = _string(delivery['status']);
    final version = _int(delivery['version']);
    final createdAt = _dateTime(delivery['createdAt']);
    final updatedAt = _dateTime(delivery['updatedAt']);
    if (id == null ||
        !_isUuid(id) ||
        salesOrderNumber == null ||
        status == null ||
        version == null ||
        version < 0 ||
        createdAt == null ||
        updatedAt == null) {
      _invalidResponse();
    }
    return BuyerDeliveryProjection(
      id: id,
      salesOrderNumber: salesOrderNumber,
      status: status,
      version: version,
      createdAt: createdAt,
      updatedAt: updatedAt,
      destination: _optionalString(delivery, 'destination'),
      scheduledAt: _optionalDateTime(delivery, 'scheduledAt'),
      dispatchedAt: _optionalDateTime(delivery, 'dispatchedAt'),
      deliveredAt: _optionalDateTime(delivery, 'deliveredAt'),
      proofOfDeliveryStatus: _optionalString(delivery, 'proofOfDeliveryStatus'),
    );
  }

  BuyerDeliveryEventProjection _parseEvent(Object? value) {
    final event = _map(value);
    if (event == null) _invalidResponse();
    final type = _string(event['type']);
    final occurredAt = _dateTime(event['occurredAt']);
    if (type == null || occurredAt == null) {
      _invalidResponse();
    }
    return BuyerDeliveryEventProjection(type: type, occurredAt: occurredAt);
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.trim().isNotEmpty ? value : null;

  String? _optionalString(Map<String, Object?> source, String key) {
    final value = source[key];
    if (value == null) return null;
    final parsed = _string(value);
    if (parsed == null) _invalidResponse();
    return parsed;
  }

  int? _int(Object? value) => value is int ? value : null;

  DateTime? _dateTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;

  num? _num(Object? value) => value is num ? value : null;

  double? _double(Object? value) => value is num ? value.toDouble() : null;

  DateTime? _optionalDateTime(Map<String, Object?> source, String key) {
    final value = source[key];
    if (value == null) return null;
    final parsed = _dateTime(value);
    if (parsed == null) _invalidResponse();
    return parsed;
  }

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  ).hasMatch(value);

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_buyer_delivery_response',
    userMessage: 'The delivery information could not be read.',
  );

  Never _invalidRequest(String code) => throw NexaApiFailure(
    code: code,
    userMessage: 'The delivery command could not be read.',
  );
}
