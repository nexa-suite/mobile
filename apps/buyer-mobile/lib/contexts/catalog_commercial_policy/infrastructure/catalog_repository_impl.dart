import '../../../core/network/nexa_api_client.dart';
import '../application/catalog_repository.dart';

final class CatalogRepositoryImpl implements CatalogRepository {
  CatalogRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<CatalogPageProjection> list({
    required String query,
    required int page,
    String brand = '',
    String category = '',
    String brandId = '',
    String categoryId = '',
    String familyId = '',
    String variantId = '',
    String coldChain = '',
    bool availableOnly = false,
  }) async {
    final selectedBrandId = brandId.trim();
    final selectedCategoryId = categoryId.trim();
    final selectedFamilyId = familyId.trim();
    final selectedVariantId = variantId.trim();
    if ((selectedBrandId.isNotEmpty && !_isUuid(selectedBrandId)) ||
        (selectedCategoryId.isNotEmpty && !_isUuid(selectedCategoryId)) ||
        (selectedFamilyId.isNotEmpty && !_isUuid(selectedFamilyId)) ||
        (selectedVariantId.isNotEmpty &&
            (!_isUuid(selectedVariantId) || selectedFamilyId.isEmpty))) {
      _invalidResponse();
    }
    final response = await _api.get(
      '/catalog-items',
      query: {
        if (query.trim().isNotEmpty) 'q': query.trim(),
        if (selectedBrandId.isNotEmpty)
          'brandId': selectedBrandId
        else if (brand.trim().isNotEmpty)
          'brand': brand.trim(),
        if (selectedCategoryId.isNotEmpty)
          'categoryId': selectedCategoryId
        else if (category.trim().isNotEmpty)
          'category': category.trim(),
        if (selectedFamilyId.isNotEmpty) 'familyId': selectedFamilyId,
        if (selectedVariantId.isNotEmpty) 'variantId': selectedVariantId,
        if (coldChain.trim().isNotEmpty) 'coldChain': coldChain.trim(),
        if (availableOnly) 'availableOnly': 'true',
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
  Future<CatalogFilterOptionPageProjection> filterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) async {
    final selectedFamilyId = familyId?.trim();
    if (page < 0 ||
        (kind == CatalogFilterKind.variant &&
            (selectedFamilyId == null || !_isUuid(selectedFamilyId))) ||
        (kind != CatalogFilterKind.variant && selectedFamilyId != null)) {
      _invalidResponse();
    }
    final response = await _api.get(
      '/catalog-items/filter-options',
      query: {
        'kind': kind.name,
        if (search.trim().isNotEmpty) 'search': search.trim(),
        ...?(selectedFamilyId == null ? null : {'familyId': selectedFamilyId}),
        'page': '$page',
        'size': '100',
      },
      refreshAfterUnauthorized: true,
    );
    final items = response.body['items'];
    final responsePage = _int(response.body['page']);
    final size = _int(response.body['size']);
    final total = _int(response.body['total']);
    if (items is! List ||
        responsePage == null ||
        size == null ||
        size < 1 ||
        total == null ||
        responsePage != page) {
      _invalidResponse();
    }
    return CatalogFilterOptionPageProjection(
      items: items
          .map((value) {
            final item = _map(value);
            final id = item == null ? null : _string(item['id']);
            final name = item == null ? null : _string(item['name']);
            if (id == null || name == null || !_isUuid(id)) _invalidResponse();
            return CatalogFilterOptionProjection(id: id, name: name);
          })
          .toList(growable: false),
      page: responsePage,
      size: size,
      total: total,
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
    final image = _map(item['image']);
    return CatalogItemProjection(
      catalogItemId: id,
      sellableSkuId: sellableSkuId,
      itemName: name,
      availabilityStatus: _string(item['availabilityStatus']) ?? 'UNKNOWN',
      presentation: _string(item['presentation']),
      brandName: _string(item['brandName']),
      categoryName: _string(item['categoryName']),
      description: _string(item['description']),
      imageUrl: _imageUrl(image?['url']),
      imageFileName: _string(image?['fileName']),
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

  String? _imageUrl(Object? value) {
    final path = _string(value);
    if (path == null) return null;
    final parsed = Uri.tryParse(path);
    if (parsed == null || parsed.userInfo.isNotEmpty) return null;
    final resolved = _api.origin.uri.resolveUri(parsed);
    // Public catalog media never receives session credentials.
    if (resolved.scheme == 'https' ||
        (resolved.scheme == 'http' &&
            resolved.origin == _api.origin.uri.origin)) {
      return resolved.toString();
    }
    return null;
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

  bool _isUuid(String value) => _uuid(value) != null;

  int? _int(Object? value) => value is int ? value : null;

  num? _num(Object? value) => value is num ? value : null;

  Never _invalidResponse() => throw const NexaApiFailure(
    code: 'invalid_catalog_response',
    userMessage: 'The catalog response could not be read.',
  );
}
