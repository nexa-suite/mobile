import 'business_documents_repository.dart';

abstract interface class BusinessDocumentExportPort {
  /// User chooses the destination; false means the system picker was cancelled.
  Future<bool> save(BusinessDocumentContentProjection content);
}
