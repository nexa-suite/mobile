final class BuyerMobileTermsDocument {
  const BuyerMobileTermsDocument({
    required this.documentId,
    required this.version,
    required this.content,
    required this.contentDigest,
  });

  final String documentId;
  final String version;
  final String content;
  final String contentDigest;
}

final class BuyerMobileTermsStatus {
  const BuyerMobileTermsStatus({
    required this.document,
    required this.accepted,
    required this.acceptedAt,
  });

  final BuyerMobileTermsDocument document;
  final bool accepted;
  final DateTime? acceptedAt;
}

final class BuyerMobileTermsAcceptance {
  const BuyerMobileTermsAcceptance({
    required this.documentId,
    required this.version,
    required this.contentDigest,
    required this.accepted,
    required this.acceptedAt,
  });

  final String documentId;
  final String version;
  final String contentDigest;
  final bool accepted;
  final DateTime? acceptedAt;
}

abstract interface class BuyerMobileTermsRepository {
  Future<BuyerMobileTermsDocument> fetchPublicTerms();

  Future<BuyerMobileTermsStatus> fetchCurrentStatus();

  Future<BuyerMobileTermsAcceptance> accept(BuyerMobileTermsDocument document);
}

enum BuyerMobileTermsFailure implements Exception {
  sessionRequired,
  accessDenied,
  documentChanged,
  connectionUnavailable,
  unconfirmed,
}
