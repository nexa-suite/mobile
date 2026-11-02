import '../domain/model/buyer_favorite.dart';
export '../domain/model/buyer_favorite.dart';

abstract interface class BuyerFavoritesRepository {
  Future<BuyerFavoritesPageProjection> list({required int page});
  Future<bool> isSaved(String sellableSkuId);
  Future<void> save(String sellableSkuId);
  Future<void> remove(String sellableSkuId);
}
