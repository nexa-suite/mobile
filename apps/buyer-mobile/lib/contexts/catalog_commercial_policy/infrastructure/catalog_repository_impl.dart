import '../../../core/network/nexa_api_client.dart';
import '../application/catalog_repository.dart';

final class CatalogRepositoryImpl implements CatalogRepository {
  CatalogRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
  }) async {
    final response = await _api.get(
      '/catalog-items',
      query: {
        if (query.trim().isNotEmpty) 'q': query.trim(),
        'page': '$page',
        'size': '20',
        'sort': 'itemName',
        'direction': 'asc',
      },
      refreshAfterUnauthorized: true,
    );
    final items = response.body['items'];
    if (items is! List) _invalidResponse();
    return CatalogPageProjection(
      items: items.map(_parseItem).toList(growable: false),
      page: _int(response.body['page']) ?? page,
      size: _int(response.body['size']) ?? 20,
      totalItems: _int(response.body['totalItems']) ?? 0,
      totalPages: _int(response.body['totalPages']) ?? 0,
    );
  }

  @override
  Future<CatalogItemProjection> detail(String catalogItemId) async {
    if (!RegExp(
      r'^CAT-[A-Z0-9-]{1,63}$',
      caseSensitive: false,
    ).hasMatch(catalogItemId)) {
      _invalidResponse();
    }
    final response = await _api.get(
      '/catalog-items/${Uri.encodeComponent(catalogItemId)}',
      refreshAfterUnauthorized: true,
    );
    final item = _parseItem(response.body);
    if (item.catalogItemId != catalogItemId) _invalidResponse();
    return item;
  }

  CatalogItemProjection _parseItem(Object? value) {
    final item = _map(value);
    if (item == null) _invalidResponse();
    final id = _string(item['catalogItemId']);
    final name = _string(item['itemName']);
    if (id == null || name == null) _invalidResponse();
    final sellableSkuId = _uuid(item['sellableSkuId']);
    final nearExpiry = item['nearExpiry'];
    return CatalogItemProjection(
      catalogItemId: id,
      sellableSkuId: sellableSkuId,
      itemName: name,
      availabilityStatus: _string(item['availabilityStatus']) ?? 'UNKNOWN',
      presentation: _string(item['presentation']),
      brandName: _string(item['brandName']),
      categoryName: _string(item['categoryName']),
      description: _string(item['description']),
      skuCode: _string(item['skuCode']),
      unitOfMeasure: _string(item['unitOfMeasure']),
      status: _string(item['status']),
      coldChainRequirement: _string(item['coldChainRequirement']),
      productVariantName: _string(item['productVariantName']),
      productFamilyName: _string(item['productFamilyName']),
      packagingType: _string(item['packagingType']),
      netWeight: _num(item['netWeight']),
      grossWeight: _num(item['grossWeight']),
      nearExpiry: nearExpiry is bool ? nearExpiry : null,
      promotionLabel: _string(item['promotionLabel']),
      sellableAvailability: _num(item['sellableAvailability']),
      pricingAsOf: _string(item['pricingAsOf']),
      availabilityAsOf: _string(item['availabilityAsOf']),
      price: _parsePrice(item['currentOfferPrice']),
    );
  }

  CatalogPriceProjection? _parsePrice(Object? value) {
    final price = _map(value);
    if (price == null) return null;
    final amountValue = price['amount'];
    final amount = amountValue is num
        ? amountValue.toString()
        : _string(amountValue);
    final currency = _string(price['currency']);
    if (amount == null || currency == null) return null;
    return CatalogPriceProjection(amount: amount, currency: currency);
  }

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  String? _uuid(Object? value) {
    final candidate = _string(value);
    if (candidate == null ||
        !RegExp(
          r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
        ).hasMatch(candidate)) {
      return null;
    }
    return candidate;
  }

  int? _int(Object? value) => value is int ? value : null;

  num? _num(Object? value) => value is num ? value : null;

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_catalog_response',
    userMessage: 'The catalog response could not be read.',
  );
}
