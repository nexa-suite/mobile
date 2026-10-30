final class BuyerFavorite {
  const BuyerFavorite({
    required this.sellableSkuId,
    this.catalogItemId,
    this.name,
    this.brand,
    this.amount,
    this.currency,
    this.unit,
  });
  final String sellableSkuId;
  final String? catalogItemId;
  final String? name;
  final String? brand;
  final String? amount;
  final String? currency;
  final String? unit;
}

final class BuyerFavoritesPageProjection {
  const BuyerFavoritesPageProjection({
    required this.items,
    required this.page,
    required this.totalPages,
  });
  final List<BuyerFavorite> items;
  final int page;
  final int totalPages;
}
