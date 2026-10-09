final class CatalogPriceProjection {
  const CatalogPriceProjection({required this.amount, required this.currency});

  final String amount;
  final String currency;
}

final class CatalogItemProjection {
  const CatalogItemProjection({
    required this.catalogItemId,
    this.sellableSkuId,
    required this.itemName,
    required this.availabilityStatus,
    this.presentation,
    this.brandName,
    this.categoryName,
    this.description,
    this.skuCode,
    this.unitOfMeasure,
    this.status,
    this.coldChainRequirement,
    this.productVariantName,
    this.productFamilyName,
    this.packagingType,
    this.netWeight,
    this.grossWeight,
    this.nearExpiry,
    this.promotionLabel,
    this.sellableAvailability,
    this.pricingAsOf,
    this.availabilityAsOf,
    this.price,
  });

  final String catalogItemId;
  final String? sellableSkuId;
  final String itemName;
  final String availabilityStatus;
  final String? presentation;
  final String? brandName;
  final String? categoryName;
  final String? description;
  final String? skuCode;
  final String? unitOfMeasure;
  final String? status;
  final String? coldChainRequirement;
  final String? productVariantName;
  final String? productFamilyName;
  final String? packagingType;
  final num? netWeight;
  final num? grossWeight;
  final bool? nearExpiry;
  final String? promotionLabel;
  final num? sellableAvailability;
  final String? pricingAsOf;
  final String? availabilityAsOf;
  final CatalogPriceProjection? price;
}

final class CatalogPageProjection {
  const CatalogPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.totalItems,
    required this.totalPages,
  });

  final List<CatalogItemProjection> items;
  final int page;
  final int size;
  final int totalItems;
  final int totalPages;
}

abstract interface class CatalogRepository {
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
  });

  Future<CatalogItemProjection> detail(String catalogItemId);
}
