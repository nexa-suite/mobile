import '../../../core/network/nexa_api_client.dart';
import '../application/business_documents_repository.dart';

final class BusinessDocumentsRepositoryImpl
    implements BusinessDocumentsRepository {
  BusinessDocumentsRepositoryImpl(this._api);

  final NexaApiClient _api;

  static const _formats = {
    'PDF': 'application/pdf',
    'CSV': 'text/csv',
    'XML': 'application/xml',
  };
  static const _statuses = {
    'REQUESTED',
    'GENERATING',
    'GENERATED',
    'FAILED',
    'SUPERSEDED',
    'VOIDED',
  };

  @override
  Future<BusinessDocumentsPageProjection> list({required int page}) async {
    if (page < 0) _invalidResponse();
    final response = await _api.get(
      '/business-documents',
      query: {'page': '$page', 'size': '25'},
      refreshAfterUnauthorized: true,
    );
    final rawItems = response.body['items'];
    final resultPage = _int(response.body['page']);
    final size = _int(response.body['size']);
    final total = _int(response.body['total']);
    if (rawItems is! List ||
        resultPage == null ||
        resultPage != page ||
        size == null ||
        size < 1 ||
        total == null ||
        total < 0) {
      _invalidResponse();
    }
    final items = rawItems.map(_parseDocument).toList(growable: false);
    if (items.length > size || total < items.length) _invalidResponse();
    final identifiers = items.map((item) => item.id).toSet();
    if (identifiers.length != items.length) _invalidResponse();
    return BusinessDocumentsPageProjection(
      items: items,
      page: resultPage,
      size: size,
      total: total,
    );
  }

  @override
  Future<BusinessDocumentProjection> detail(String documentId) async {
    if (!_isUuid(documentId)) _invalidResponse();
    final response = await _api.get(
      '/business-documents/${Uri.encodeComponent(documentId)}',
      refreshAfterUnauthorized: true,
    );
    final document = _parseDocument(response.body);
    if (document.id != documentId) _invalidResponse();
    return document;
  }

  @override
  Future<BusinessDocumentContentProjection> download(
    BusinessDocumentProjection document,
  ) async {
    if (!_isUuid(document.id) ||
        !document.contentAvailable ||
        document.byteSize > NexaApiClient.maxProtectedBinaryBytes ||
        _formats[document.format] != document.contentType ||
        !_isSha256(document.checksumSha256)) {
      throw const NexaApiFailure(
        code: 'document_not_downloadable',
        statusCode: 409,
        userMessage: 'This document is not ready to download.',
      );
    }

    final response = await _api.getBinary(
      '/business-documents/${Uri.encodeComponent(document.id)}/downloads',
      refreshAfterUnauthorized: true,
    );
    try {
      final headers = {
        for (final entry in response.headers.entries)
          entry.key.toLowerCase(): entry.value.trim(),
      };
      final contentType = headers['content-type']?.split(';').first.trim();
      final checksumHeader = headers['x-content-sha256'];
      final checksum = response.sha256;
      if (response.statusCode != 200 ||
          response.bytes.length != document.byteSize ||
          contentType != document.contentType ||
          !_isSha256(checksumHeader) ||
          !_isSha256(document.checksumSha256) ||
          checksum != document.checksumSha256!.toLowerCase() ||
          checksum != checksumHeader!.toLowerCase()) {
        throw const NexaApiFailure(
          code: 'document_integrity_check_failed',
          statusCode: 502,
          userMessage: 'The downloaded document could not be verified.',
        );
      }
      final content = BusinessDocumentContentProjection(
        document: document,
        bytes: response.bytes,
        sha256: checksum,
      );
      response.clear();
      return content;
    } catch (_) {
      response.clear();
      rethrow;
    }
  }

  BusinessDocumentProjection _parseDocument(Object? value) {
    final document = _map(value);
    if (document == null) _invalidResponse();
    final id = _string(document['id']);
    final accountId = _string(document['clientAccountId']);
    final subjectType = _string(document['subjectType']);
    final subjectId = _string(document['subjectId']);
    final documentType = _string(document['documentType']);
    final version = _int(document['version']);
    final status = _string(document['status']);
    final format = _string(document['format']);
    final byteSize = _int(document['byteSize']);
    final createdAt = _dateTime(document['createdAt']);
    final updatedAt = _dateTime(document['updatedAt']);
    final checksum = _string(document['checksumSha256']);
    final contentType = _string(document['contentType']);
    final replacementId = _string(document['replacementOfDocumentId']);
    if (id == null ||
        !_isUuid(id) ||
        accountId == null ||
        !_isUuid(accountId) ||
        subjectType == null ||
        subjectId == null ||
        !_isUuid(subjectId) ||
        documentType == null ||
        version == null ||
        version < 1 ||
        status == null ||
        !_statuses.contains(status) ||
        format == null ||
        !_formats.containsKey(format) ||
        byteSize == null ||
        byteSize < 0 ||
        createdAt == null ||
        updatedAt == null ||
        (replacementId != null && !_isUuid(replacementId))) {
      _invalidResponse();
    }
    if (status == 'GENERATED' || status == 'SUPERSEDED') {
      if (!_isSha256(checksum) ||
          _formats[format] != contentType ||
          byteSize < 1) {
        _invalidResponse();
      }
    }
    return BusinessDocumentProjection(
      id: id,
      clientAccountId: accountId,
      subjectType: subjectType,
      subjectId: subjectId,
      documentType: documentType,
      documentNumber: _string(document['documentNumber']),
      version: version,
      status: status,
      format: format,
      contentType: contentType,
      checksumSha256: checksum,
      byteSize: byteSize,
      generatedAt: _dateTime(document['generatedAt']),
      createdAt: createdAt,
      updatedAt: updatedAt,
      replacementOfDocumentId: replacementId,
    );
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  int? _int(Object? value) => value is int ? value : null;

  DateTime? _dateTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;

  bool _isUuid(String value) => RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  ).hasMatch(value);

  bool _isSha256(String? value) =>
      value != null && RegExp(r'^[0-9a-fA-F]{64}$').hasMatch(value);

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_business_document_response',
    userMessage: 'The document information could not be read.',
  );
}
