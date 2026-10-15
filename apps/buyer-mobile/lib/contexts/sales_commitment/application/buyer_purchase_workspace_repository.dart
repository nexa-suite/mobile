import 'purchase_request_repository.dart';

final class BuyerRequestLine {
  const BuyerRequestLine({
    required this.name,
    required this.quantity,
    required this.unit,
    this.presentation,
    this.price,
    this.currency,
    this.notes,
  });
  final String name;
  final num quantity;
  final String unit;
  final String? presentation;
  final num? price;
  final String? currency;
  final String? notes;
}

final class BuyerRequestTerms {
  const BuyerRequestTerms({
    this.priority,
    this.deliveryDate,
    this.paymentOption,
    this.comment,
    this.deliveryProfile,
    required this.lines,
  });
  final String? priority;
  final String? deliveryDate;
  final String? paymentOption;
  final String? comment;
  final String? deliveryProfile;
  final List<BuyerRequestLine> lines;
}

final class BuyerRequestProjection {
  const BuyerRequestProjection({
    required this.id,
    required this.code,
    required this.status,
    required this.version,
    required this.etag,
    required this.lineCount,
    required this.terms,
    this.reviewNote,
    this.expiresAt,
  });
  final String id;
  final String code;
  final String status;
  final int version;
  final String etag;
  final int lineCount;
  final BuyerRequestTerms terms;
  final String? reviewNote;
  final String? expiresAt;
}

final class BuyerRequestPage {
  const BuyerRequestPage({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });
  final List<BuyerRequestProjection> items;
  final int page;
  final int size;
  final int total;
}

final class BuyerRequestEvent {
  const BuyerRequestEvent({
    required this.id,
    required this.type,
    this.fromStatus,
    this.toStatus,
    this.occurredAt,
  });
  final String id;
  final String type;
  final String? fromStatus;
  final String? toStatus;
  final DateTime? occurredAt;
}

final class BuyerMaterialChange {
  const BuyerMaterialChange({
    required this.id,
    required this.status,
    required this.reason,
    required this.requestVersion,
    required this.original,
    required this.proposed,
  });
  final String id;
  final String status;
  final String reason;
  final int requestVersion;
  final BuyerRequestTerms original;
  final BuyerRequestTerms proposed;
}

enum BuyerRequestDecision { withdraw, acceptChange, rejectChange }

/// Buyer-owned request projections and draft composition. The API decides every
/// lifecycle transition; command keys survive explicit retries of one version.
abstract interface class BuyerPurchaseWorkspaceRepository {
  Future<BuyerRequestPage> listRequests({required int page, String? status});
  Future<BuyerRequestProjection> request(String id);
  Future<List<BuyerRequestEvent>> events(String id);
  Future<BuyerMaterialChange?> currentChange(String id);
  Future<BuyerRequestProjection> decide({
    required BuyerRequestProjection request,
    required BuyerRequestDecision decision,
    required String scopeKey,
    BuyerMaterialChange? proposal,
  });
  Future<PurchaseRequestDraftProjection> replaceDraftLines({
    required PurchaseRequestDraftProjection draft,
    required List<PurchaseRequestLineProjection> lines,
  });
}
