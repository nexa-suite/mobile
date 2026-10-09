import 'dart:typed_data';

final class BusinessDocumentProjection {
  const BusinessDocumentProjection({
    required this.id,
    required this.clientAccountId,
    required this.subjectType,
    required this.subjectId,
    required this.documentType,
    required this.version,
    required this.status,
    required this.format,
    required this.byteSize,
    required this.createdAt,
    required this.updatedAt,
    this.documentNumber,
    this.checksumSha256,
    this.contentType,
    this.generatedAt,
    this.replacementOfDocumentId,
  });

  final String id;
  final String clientAccountId;
  final String subjectType;
  final String subjectId;
  final String documentType;
  final String? documentNumber;
  final int version;
  final String status;
  final String format;
  final String? contentType;
  final String? checksumSha256;
  final int byteSize;
  final DateTime? generatedAt;
  final DateTime createdAt;
  final DateTime updatedAt;
  final String? replacementOfDocumentId;

  bool get contentAvailable =>
      (status == 'GENERATED' || status == 'SUPERSEDED') &&
      contentType != null &&
      checksumSha256 != null &&
      byteSize > 0;
}

final class BusinessDocumentsPageProjection {
  const BusinessDocumentsPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });

  final List<BusinessDocumentProjection> items;
  final int page;
  final int size;
  final int total;
}

final class BusinessDocumentContentProjection {
  BusinessDocumentContentProjection({
    required this.document,
    required List<int> bytes,
    required this.sha256,
  }) : bytes = Uint8List.fromList(bytes);

  final BusinessDocumentProjection document;
  final Uint8List bytes;
  final String sha256;

  void clear() => bytes.fillRange(0, bytes.length, 0);
}

abstract interface class BusinessDocumentsRepository {
  Future<BusinessDocumentsPageProjection> list({required int page});

  Future<BusinessDocumentProjection> detail(String documentId);

  Future<BusinessDocumentContentProjection> download(
    BusinessDocumentProjection document,
  );
}
