import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_favorites_repository.dart';

final class BuyerFavoritesRepositoryImpl implements BuyerFavoritesRepository {
  BuyerFavoritesRepositoryImpl(this._api);
  final NexaApiClient _api;
  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );

  @override
  Future<BuyerFavoritesPageProjection> list({required int page}) async {
    final response = await _api.get(
      '/buyer/favorites',
      query: {'page': '$page', 'size': '25'},
      refreshAfterUnauthorized: true,
    );
    final body = response.body;
    final items = body['items'];
    if (items is! List ||
        body['page'] != page ||
        body['totalPages'] is! int ||
        (body['totalPages'] as int) < 0) {
      _invalid();
    }
    return BuyerFavoritesPageProjection(
      items: items.map(_favorite).toList(growable: false),
      page: page,
      totalPages: body['totalPages'] as int,
    );
  }

  BuyerFavorite _favorite(Object? value) {
    if (value is! Map) _invalid();
    final sku = value['sellableSkuId'];
    if (sku is! String || !_uuid.hasMatch(sku)) _invalid();
    final offer = value['currentOffer'];
    if (offer == null) return BuyerFavorite(sellableSkuId: sku);
    if (offer is! Map || offer['sellableSkuId'] != sku) _invalid();
    String? text(Object? value) =>
        value is String && value.trim().isNotEmpty ? value : null;
    final id = text(offer['catalogItemId']);
    final name = text(offer['itemName']);
    if (id == null || name == null) _invalid();
    final price = offer['currentPrice'];
    return BuyerFavorite(
      sellableSkuId: sku,
      catalogItemId: id,
      name: name,
      brand: text(offer['brandName']),
      unit: text(offer['unitOfMeasure']),
      amount: price is Map ? text(price['amount']) : null,
      currency: price is Map ? text(price['currency']) : null,
    );
  }

  @override
  Future<bool> isSaved(String sellableSkuId) async {
    if (!_uuid.hasMatch(sellableSkuId)) _invalid();
    final body = (await _api.get(
      '/buyer/favorites/$sellableSkuId',
      refreshAfterUnauthorized: true,
    )).body;
    if (body['sellableSkuId'] != sellableSkuId || body['saved'] is! bool) {
      _invalid();
    }
    return body['saved'] as bool;
  }

  @override
  Future<void> save(String sellableSkuId) async {
    if (!_uuid.hasMatch(sellableSkuId)) _invalid();
    final response = await _api.request(
      'PUT',
      '/buyer/favorites/$sellableSkuId',
      refreshAfterUnauthorized: true,
    );
    if (_favorite(response.body).sellableSkuId != sellableSkuId) _invalid();
  }

  @override
  Future<void> remove(String sellableSkuId) async {
    if (!_uuid.hasMatch(sellableSkuId)) _invalid();
    await _api.request(
      'DELETE',
      '/buyer/favorites/$sellableSkuId',
      refreshAfterUnauthorized: true,
    );
  }

  Never _invalid() => throw const NexaApiFailure(
    code: 'buyer_favorites_response_invalid',
    userMessage: 'No pudimos confirmar tus favoritos. Vuelve a intentarlo.',
    retryable: true,
  );
}
