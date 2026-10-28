import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/catalog_repository.dart';

enum CatalogLoadStatus { idle, loading, current, unavailable, permissionDenied }

enum CatalogFilterOptionsStatus {
  idle,
  loading,
  current,
  unavailable,
  permissionDenied,
}

final class CatalogFilterOptionsState {
  const CatalogFilterOptionsState({
    this.status = CatalogFilterOptionsStatus.idle,
    this.items = const [],
    this.search = '',
    this.page = 0,
    this.size = 100,
    this.total = 0,
    this.message,
  });

  final CatalogFilterOptionsStatus status;
  final List<CatalogFilterOptionProjection> items;
  final String search;
  final int page;
  final int size;
  final int total;
  final String? message;

  int get totalPages => size == 0 ? 0 : (total / size).ceil();
}

final class CatalogViewModel extends ChangeNotifier {
  CatalogViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final CatalogRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;
  final Map<CatalogFilterKind, int> _lookupGenerations = {
    CatalogFilterKind.brand: 0,
    CatalogFilterKind.category: 0,
    CatalogFilterKind.family: 0,
    CatalogFilterKind.variant: 0,
  };

  CatalogLoadStatus status = CatalogLoadStatus.idle;
  List<CatalogItemProjection> items = const [];
  String query = '';
  String brand = '';
  String brandId = '';
  String category = '';
  String categoryId = '';
  String family = '';
  String familyId = '';
  String variant = '';
  String variantId = '';
  int page = 0;
  int totalItems = 0;
  int totalPages = 0;
  DateTime? updatedAt;
  String? message;
  Map<CatalogFilterKind, CatalogFilterOptionsState> filterOptionStates = {
    CatalogFilterKind.brand: const CatalogFilterOptionsState(),
    CatalogFilterKind.category: const CatalogFilterOptionsState(),
    CatalogFilterKind.family: const CatalogFilterOptionsState(),
    CatalogFilterKind.variant: const CatalogFilterOptionsState(),
  };

  static const readPermission = 'catalog.read';

  Future<void> search(String value) => _load(
    query: value.trim(),
    brand: brand,
    brandId: brandId,
    category: category,
    categoryId: categoryId,
    family: family,
    familyId: familyId,
    variant: variant,
    variantId: variantId,
    page: 0,
  );

  Future<void> filter({
    String? query,
    required String brand,
    required String category,
    String? brandId,
    String? categoryId,
    String? family,
    String? familyId,
    String? variant,
    String? variantId,
  }) => _load(
    query: query?.trim() ?? this.query,
    brand: brand.trim(),
    brandId:
        brandId?.trim() ?? (brand.trim() == this.brand ? this.brandId : ''),
    category: category.trim(),
    categoryId:
        categoryId?.trim() ??
        (category.trim() == this.category ? this.categoryId : ''),
    family: family?.trim() ?? this.family,
    familyId:
        familyId?.trim() ??
        ((family?.trim() ?? this.family) == this.family ? this.familyId : ''),
    variant: variant?.trim() ?? this.variant,
    variantId:
        variantId?.trim() ??
        ((variant?.trim() ?? this.variant) == this.variant
            ? this.variantId
            : ''),
    page: 0,
  );

  Future<void> refresh() => _load(
    query: query,
    brand: brand,
    brandId: brandId,
    category: category,
    categoryId: categoryId,
    family: family,
    familyId: familyId,
    variant: variant,
    variantId: variantId,
    page: page,
  );

  CatalogFilterOptionsState filterOptionsState(CatalogFilterKind kind) =>
      filterOptionStates[kind] ?? const CatalogFilterOptionsState();

  Future<void> loadFilterOptions({
    required CatalogFilterKind kind,
    required String search,
    required int page,
    String? familyId,
  }) async {
    final snapshot = _access.snapshot;
    final lease = _leaseKey;
    if (lease == null) {
      _setFilterOptionsState(
        kind,
        CatalogFilterOptionsState(
          status: CatalogFilterOptionsStatus.unavailable,
          search: search,
          page: page,
          message: 'Inicia sesión para consultar las opciones del catálogo.',
        ),
      );
      return;
    }
    if (!_hasReadPermission(snapshot)) {
      _lookupGenerations[kind] = _lookupGenerations[kind]! + 1;
      _setFilterOptionsState(
        kind,
        CatalogFilterOptionsState(
          status: CatalogFilterOptionsStatus.permissionDenied,
          search: search,
          page: page,
          message: 'No tienes permiso para consultar estas opciones.',
        ),
      );
      return;
    }
    if (page < 0) return;
    final selectedFamilyId = kind == CatalogFilterKind.variant
        ? familyId ?? this.familyId
        : null;
    if (kind == CatalogFilterKind.variant &&
        (selectedFamilyId == null || selectedFamilyId.isEmpty)) {
      _lookupGenerations[kind] = _lookupGenerations[kind]! + 1;
      _setFilterOptionsState(
        kind,
        const CatalogFilterOptionsState(
          status: CatalogFilterOptionsStatus.unavailable,
          message: 'Elige una familia para consultar sus variedades.',
        ),
      );
      return;
    }
    final generation = _lookupGenerations[kind]! + 1;
    _lookupGenerations[kind] = generation;
    _setFilterOptionsState(
      kind,
      CatalogFilterOptionsState(
        status: CatalogFilterOptionsStatus.loading,
        search: search,
        page: page,
      ),
    );
    try {
      final result = await _repository.filterOptions(
        kind: kind,
        search: search,
        page: page,
        familyId: selectedFamilyId,
      );
      if (!_isCurrentLookup(kind, generation, lease)) return;
      filterOptionStates = {
        ...filterOptionStates,
        kind: CatalogFilterOptionsState(
          status: CatalogFilterOptionsStatus.current,
          items: result.items,
          search: search,
          page: result.page,
          size: result.size,
          total: result.total,
        ),
      };
    } on NexaApiFailure catch (failure) {
      if (!_isCurrentLookup(kind, generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      filterOptionStates = {
        ...filterOptionStates,
        kind: CatalogFilterOptionsState(
          status: failure.statusCode == 403
              ? CatalogFilterOptionsStatus.permissionDenied
              : CatalogFilterOptionsStatus.unavailable,
          search: search,
          page: page,
          message: failure.statusCode == 403
              ? 'No tienes permiso para consultar estas opciones.'
              : failure.userMessage,
        ),
      };
    } catch (_) {
      if (!_isCurrentLookup(kind, generation, lease)) return;
      filterOptionStates = {
        ...filterOptionStates,
        kind: CatalogFilterOptionsState(
          status: CatalogFilterOptionsStatus.unavailable,
          search: search,
          page: page,
          message: 'No se pudieron consultar las opciones del catálogo.',
        ),
      };
    }
    notifyListeners();
  }

  Future<void> selectFilterOption({
    required CatalogFilterKind kind,
    CatalogFilterOptionProjection? option,
  }) {
    switch (kind) {
      case CatalogFilterKind.brand:
        return filter(
          brand: option?.name ?? '',
          brandId: option?.id ?? '',
          category: category,
          categoryId: categoryId,
          family: family,
          familyId: familyId,
          variant: variant,
          variantId: variantId,
        );
      case CatalogFilterKind.category:
        return filter(
          brand: brand,
          brandId: brandId,
          category: option?.name ?? '',
          categoryId: option?.id ?? '',
          family: family,
          familyId: familyId,
          variant: variant,
          variantId: variantId,
        );
      case CatalogFilterKind.family:
        _lookupGenerations[CatalogFilterKind.variant] =
            _lookupGenerations[CatalogFilterKind.variant]! + 1;
        filterOptionStates = {
          ...filterOptionStates,
          CatalogFilterKind.variant: const CatalogFilterOptionsState(),
        };
        return filter(
          brand: brand,
          brandId: brandId,
          category: category,
          categoryId: categoryId,
          family: option?.name ?? '',
          familyId: option?.id ?? '',
          variant: '',
          variantId: '',
        );
      case CatalogFilterKind.variant:
        if (familyId.isEmpty) return Future.value();
        return filter(
          brand: brand,
          brandId: brandId,
          category: category,
          categoryId: categoryId,
          family: family,
          familyId: familyId,
          variant: option?.name ?? '',
          variantId: option?.id ?? '',
        );
    }
  }

  Future<void> nextPage() {
    if (status != CatalogLoadStatus.current || page + 1 >= totalPages) {
      return Future.value();
    }
    return _load(
      query: query,
      brand: brand,
      brandId: brandId,
      category: category,
      categoryId: categoryId,
      family: family,
      familyId: familyId,
      variant: variant,
      variantId: variantId,
      page: page + 1,
    );
  }

  Future<void> previousPage() {
    if (status != CatalogLoadStatus.current || page == 0) {
      return Future.value();
    }
    return _load(
      query: query,
      brand: brand,
      brandId: brandId,
      category: category,
      categoryId: categoryId,
      family: family,
      familyId: familyId,
      variant: variant,
      variantId: variantId,
      page: page - 1,
    );
  }

  Future<void> _load({
    required String query,
    required String brand,
    required String brandId,
    required String category,
    required String categoryId,
    required String family,
    required String familyId,
    required String variant,
    required String variantId,
    required int page,
  }) async {
    final snapshot = _access.snapshot;
    final lease = _leaseKey;
    if (lease == null) {
      _requestGeneration++;
      status = CatalogLoadStatus.unavailable;
      items = const [];
      message = 'Inicia sesión para consultar el catálogo.';
      notifyListeners();
      return;
    }
    if (!snapshot.hasActiveBuyerScope ||
        !snapshot.currentContext!.permissions.contains(readPermission)) {
      _requestGeneration++;
      status = CatalogLoadStatus.permissionDenied;
      items = const [];
      message = 'No tienes permiso para consultar el catálogo.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    final sameFilters =
        this.query == query &&
        this.brandId == brandId &&
        this.categoryId == categoryId &&
        this.familyId == familyId &&
        this.variantId == variantId;
    status = CatalogLoadStatus.loading;
    this.query = query;
    this.brand = brand;
    this.brandId = brandId;
    this.category = category;
    this.categoryId = categoryId;
    this.family = family;
    this.familyId = familyId;
    this.variant = variant;
    this.variantId = variantId;
    message = null;
    if (!sameFilters) {
      items = const [];
      totalItems = 0;
      totalPages = 0;
      updatedAt = null;
    }
    notifyListeners();

    try {
      final result = await _repository.list(
        query: query,
        brand: brand,
        category: category,
        brandId: brandId,
        categoryId: categoryId,
        familyId: familyId,
        variantId: variantId,
        page: page,
      );
      if (!_isCurrent(generation, lease)) return;
      items = result.items;
      this.page = result.page;
      totalItems = result.totalItems;
      totalPages = result.totalPages;
      updatedAt = DateTime.now();
      status = CatalogLoadStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      if (failure.statusCode == 403) {
        status = CatalogLoadStatus.permissionDenied;
        items = const [];
        page = 0;
        totalItems = 0;
        totalPages = 0;
        message = 'No tienes permiso para consultar el catálogo.';
      } else {
        status = CatalogLoadStatus.unavailable;
        message = failure.userMessage;
      }
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = CatalogLoadStatus.unavailable;
      message = 'The catalog could not be refreshed.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration && lease == _leaseKey;

  bool _isCurrentLookup(CatalogFilterKind kind, int generation, String lease) {
    if (generation != _lookupGenerations[kind] || lease != _leaseKey) {
      return false;
    }
    final snapshot = _access.snapshot;
    if (lease != _activeLease(snapshot) || !_hasReadPermission(snapshot)) {
      _onAccessChanged(snapshot);
      return false;
    }
    return true;
  }

  bool _hasReadPermission(BuyerAccessSnapshot snapshot) =>
      snapshot.hasActiveBuyerScope &&
      snapshot.currentContext!.permissions.contains(readPermission);

  void _setFilterOptionsState(
    CatalogFilterKind kind,
    CatalogFilterOptionsState value,
  ) {
    filterOptionStates = {...filterOptionStates, kind: value};
    notifyListeners();
  }

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextScope = _activeLease(snapshot);
    if (nextScope == _leaseKey) return;
    _leaseKey = nextScope;
    _requestGeneration++;
    for (final kind in CatalogFilterKind.values) {
      _lookupGenerations[kind] = _lookupGenerations[kind]! + 1;
    }
    status = CatalogLoadStatus.idle;
    items = const [];
    query = '';
    brand = '';
    brandId = '';
    category = '';
    categoryId = '';
    family = '';
    familyId = '';
    variant = '';
    variantId = '';
    page = 0;
    totalItems = 0;
    totalPages = 0;
    updatedAt = null;
    message = null;
    filterOptionStates = {
      CatalogFilterKind.brand: const CatalogFilterOptionsState(),
      CatalogFilterKind.category: const CatalogFilterOptionsState(),
      CatalogFilterKind.family: const CatalogFilterOptionsState(),
      CatalogFilterKind.variant: const CatalogFilterOptionsState(),
    };
    notifyListeners();
  }

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.hasActiveBuyerScope
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
