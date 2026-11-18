import '../../../core/network/nexa_api_client.dart';
import '../../../core/application/evidence_upload_policy.dart';
import '../application/buyer_payments_repository.dart';
import '../application/buyer_transfer_proof_repository.dart';

final class BuyerPaymentsRepositoryImpl
    implements BuyerPaymentsRepository, BuyerTransferProofRepository {
  BuyerPaymentsRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<BuyerPaymentHistoryPageProjection> listForReceivable({
    required String receivableId,
    required int page,
    String? expectedClientAccountId,
  }) async {
    if (!_isUuid(receivableId) || page < 0) _invalidResponse();
    final response = await _api.get(
      '/receivables/$receivableId/payments',
      query: {'page': '$page', 'size': '25'},
      refreshAfterUnauthorized: true,
    );
    final rawItems = response.body['items'];
    final responsePage = _integer(response.body['page']);
    final size = _integer(response.body['size']);
    final total = _integer(response.body['total']);
    if (rawItems is! List ||
        responsePage != page ||
        size == null ||
        size < 1 ||
        size > 100 ||
        total == null ||
        total < 0) {
      _invalidResponse();
    }
    final items = rawItems
        .map(
          (value) => _parsePayment(
            value,
            expectedReceivableId: receivableId,
            expectedClientAccountId: expectedClientAccountId,
          ),
        )
        .toList(growable: false);
    if (items.length > size) _invalidResponse();
    return BuyerPaymentHistoryPageProjection(
      items: items,
      page: responsePage!,
      size: size,
      total: total,
    );
  }

  @override
  Future<BuyerPaymentProjection> reportBankTransfer({
    required String receivableId,
    required String reference,
    required String idempotencyKey,
    String? proofEvidenceId,
  }) async {
    final normalizedReference = reference.trim();
    if (!_isUuid(receivableId) ||
        normalizedReference.isEmpty ||
        normalizedReference.length > 160 ||
        !_isUuid(idempotencyKey) ||
        (proofEvidenceId != null && !_isUuid(proofEvidenceId))) {
      _invalidResponse();
    }
    final response = await _api.post(
      '/receivables/$receivableId/bank-transfer-payments',
      idempotencyKey: idempotencyKey,
      body: {
        'reference': normalizedReference,
        'proofEvidenceId': proofEvidenceId,
      },
    );
    return _parsePayment(
      response.body,
      expectedReceivableId: receivableId,
      requireBankTransfer: true,
    );
  }

  @override
  Future<BuyerTransferProofProjection> uploadTransferProof({
    required String receivableId,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
  }) async {
    final name = filename.trim();
    const allowed = {
      'jpg': 'image/jpeg',
      'jpeg': 'image/jpeg',
      'png': 'image/png',
      'pdf': 'application/pdf',
    };
    if (!_isUuid(receivableId) ||
        !_isUuid(idempotencyKey) ||
        name.isEmpty ||
        name.length > 255 ||
        RegExp(r'[/\\\r\n]').hasMatch(name) ||
        allowed[name.split('.').last.toLowerCase()] != contentType ||
        bytes.isEmpty ||
        bytes.length > evidenceUploadMaximumBytes) {
      throw const NexaApiFailure(
        code: 'invalid_transfer_proof',
        userMessage: 'Selecciona un archivo JPG, PNG o PDF de hasta 10 MiB.',
      );
    }
    final response = await _api.postMultipart(
      path: '/business-document-evidence',
      fields: {'subjectType': 'RECEIVABLE', 'subjectId': receivableId},
      fieldName: 'file',
      filename: name,
      contentType: contentType,
      bytes: bytes,
      idempotencyKey: idempotencyKey,
      refreshAfterUnauthorized: false,
    );
    return _parseProof(response.body, receivableId);
  }

  @override
  Future<BuyerTransferProofProjection> transferProof({
    required String receivableId,
    required String evidenceId,
  }) async {
    if (!_isUuid(receivableId) || !_isUuid(evidenceId)) _invalidResponse();
    final response = await _api.get(
      '/business-document-evidence/$evidenceId',
      refreshAfterUnauthorized: true,
    );
    final proof = _parseProof(response.body, receivableId);
    if (proof.id != evidenceId) _invalidResponse();
    return proof;
  }

  BuyerTransferProofProjection _parseProof(
    Map<String, Object?> value,
    String receivableId,
  ) {
    final id = _string(value['id']);
    final filename = _string(value['originalFilename']);
    final status = _string(value['lifecycleStatus']);
    const statuses = {
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
        filename == null ||
        value['subjectType'] != 'RECEIVABLE' ||
        value['subjectId'] != receivableId ||
        !statuses.contains(status)) {
      _invalidResponse();
    }
    return BuyerTransferProofProjection(
      id: id,
      receivableId: receivableId,
      filename: filename,
      lifecycleStatus: status!,
    );
  }

  BuyerPaymentProjection _parsePayment(
    Object? value, {
    required String expectedReceivableId,
    bool requireBankTransfer = false,
    String? expectedClientAccountId,
  }) {
    if (value is! Map<String, Object?>) _invalidResponse();
    final id = _string(value['id']);
    final receivableId = _string(value['receivableId']);
    final method = _string(value['method']);
    final status = _string(value['status']);
    final clientAccountId = _string(value['clientAccountId']);
    final amount = _amount(value['amount']);
    final currency = _string(value['currency']);
    final createdAt = _dateTime(value['createdAt']);
    final completedAt = value['completedAt'] == null
        ? null
        : _dateTime(value['completedAt']);
    if (id == null ||
        !_isUuid(id) ||
        receivableId == null ||
        receivableId != expectedReceivableId ||
        (expectedClientAccountId != null &&
            clientAccountId != expectedClientAccountId) ||
        method == null ||
        (requireBankTransfer && method != 'BANK_TRANSFER') ||
        status == null ||
        amount == null ||
        currency == null ||
        !RegExp(r'^[A-Z]{3}$').hasMatch(currency) ||
        createdAt == null ||
        (value['completedAt'] != null && completedAt == null)) {
      _invalidResponse();
    }
    return BuyerPaymentProjection(
      id: id,
      receivableId: receivableId,
      method: method,
      status: status,
      amount: amount,
      currency: currency,
      reference: _string(value['reference']),
      reviewReason: _string(value['reviewReason']),
      createdAt: createdAt,
      completedAt: completedAt,
    );
  }

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  String? _amount(Object? value) {
    if (value is num && value.isFinite && value >= 0) return value.toString();
    if (value is String) {
      final parsed = num.tryParse(value);
      if (parsed != null && parsed.isFinite && parsed >= 0) return value;
    }
    return null;
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

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
    caseSensitive: false,
  ).hasMatch(value);

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_payment_response',
    userMessage: 'Payment information could not be read safely.',
  );
}
