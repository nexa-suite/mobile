import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../catalog_commercial_policy/application/catalog_repository.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/purchase_request_repository.dart';

enum PurchaseRequestComposerStatus {
  loading,
  ready,
  preparing,
  reviewReady,
  submitting,
  submitted,
  unavailable,
}

enum PurchaseRequestRecoveryStatus {
  none,
  checking,
  reviewRequired,
  unavailable,
}

final class PurchaseRequestComposerViewModel extends ChangeNotifier {
  PurchaseRequestComposerViewModel(
    this._catalog,
    this._repository,
    this._access,
    this.catalogItemId,
  ) : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final CatalogRepository _catalog;
  final PurchaseRequestRepository _repository;
  final BuyerAccessRepository _access;
  final String catalogItemId;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  PurchaseRequestComposerStatus status = PurchaseRequestComposerStatus.loading;
  PurchaseRequestRecoveryStatus recoveryStatus =
      PurchaseRequestRecoveryStatus.none;
  List<PurchaseRequestDraftSummaryProjection> recoveryCandidates = const [];
  PurchaseRequestDraftProjection? inspectedRecoveryDraft;
  int recoveryPage = 0;
  int recoveryTotalPages = 1;
  CatalogItemProjection? item;
  BuyerPurchaseContextProjection? purchaseContext;
  PurchaseRequestDraftProjection? draft;
  PurchaseRequestReviewProjection? review;
  String? selectedAddressId;
  String paymentPreference = 'BANK_TRANSFER';
  final DateTime minimumDeliveryDate = nextBusinessDate(3);
  late DateTime requestedDeliveryDate = minimumDeliveryDate;
  String? message;

  bool get canCreateRequest =>
      _hasPermission('buyer.sales.read') && _hasPermission('buyer.sales.write');

  bool get canPrepareRequest =>
      recoveryStatus == PurchaseRequestRecoveryStatus.none &&
      (status == PurchaseRequestComposerStatus.ready ||
          status == PurchaseRequestComposerStatus.reviewReady);

  List<BuyerDeliveryAddressProjection> get activeAddresses =>
      purchaseContext?.addresses.where((address) => address.active).toList() ??
      const [];

  Future<void> load() async {
    final lease = _leaseKey;
    if (lease == null) {
      _closeForChangedAccess();
      return;
    }
    if (!canCreateRequest) {
      status = PurchaseRequestComposerStatus.unavailable;
      message = 'Tu acceso no permite crear solicitudes de compra.';
      notifyListeners();
      return;
    }
    final generation = ++_requestGeneration;
    var recoveryStateChecked = false;
    status = PurchaseRequestComposerStatus.loading;
    recoveryStatus = PurchaseRequestRecoveryStatus.none;
    recoveryCandidates = const [];
    inspectedRecoveryDraft = null;
    recoveryPage = 0;
    recoveryTotalPages = 1;
    item = null;
    purchaseContext = null;
    draft = null;
    review = null;
    message = null;
    notifyListeners();
    try {
      final loadedItem = await _catalog.detail(catalogItemId);
      if (!_isCurrent(generation, lease)) return;
      if (!_isUuid(loadedItem.sellableSkuId)) {
        throw const NexaApiFailure(
          code: 'sellable_sku_unavailable',
          userMessage:
              'Este producto no tiene un SKU de venta válido para solicitar.',
        );
      }
      final context = await _repository.loadBuyerPurchaseContext();
      if (!_isCurrent(generation, lease)) return;
      final currentMembershipId = _access.snapshot.currentContext?.membershipId;
      if (context.buyerMembershipId != currentMembershipId) {
        throw const NexaApiFailure(
          code: 'buyer_relationship_mismatch',
          userMessage:
              'La cuenta Buyer no coincide con el contexto de acceso actual.',
        );
      }
      item = loadedItem;
      purchaseContext = context;
      final defaultAddresses = context.addresses.where(
        (address) => address.active && address.defaultAddress,
      );
      selectedAddressId = defaultAddresses.isEmpty
          ? null
          : defaultAddresses.first.id;
      final scopeKey = _access.snapshot.currentContext!.scopeKey;
      final intentKey = _creationIntentKey(scopeKey, loadedItem.sellableSkuId!);
      final record = await _repository.creationRecord(intentKey);
      recoveryStateChecked = true;
      if (!_isCurrent(generation, lease)) return;
      if (record != null) {
        if (record.isUnknown) {
          recoveryStatus = PurchaseRequestRecoveryStatus.checking;
          notifyListeners();
          final page = await _repository.listDrafts(page: 0);
          if (!_isCurrent(generation, lease)) return;
          recoveryCandidates = page.items;
          recoveryPage = page.page;
          recoveryTotalPages = page.totalPages;
          recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
          status = PurchaseRequestComposerStatus.ready;
          message = _uncertainCreationMessage;
          notifyListeners();
          return;
        }

        final recovered = await _repository.getDraft(record.draftId!);
        if (!_isCurrent(generation, lease)) return;
        _requireSameBuyerAccount(recovered, context.clientAccountId);
        if (recovered.status == 'SUBMITTED') {
          draft = recovered;
          status = PurchaseRequestComposerStatus.submitted;
          await _clearCompletedCreationRecord(intentKey);
          notifyListeners();
          return;
        }
        if (!_canResumeForCurrentItem(recovered, loadedItem)) {
          recoveryStatus = PurchaseRequestRecoveryStatus.checking;
          final page = await _repository.listDrafts(page: 0);
          if (!_isCurrent(generation, lease)) return;
          recoveryCandidates = page.items;
          recoveryPage = page.page;
          recoveryTotalPages = page.totalPages;
          recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
          status = PurchaseRequestComposerStatus.ready;
          message = _knownDraftMismatchMessage;
          notifyListeners();
          return;
        }
        final serverReview = await _repository.review(recovered.id);
        if (!_isCurrent(generation, lease)) return;
        _requireSameBuyerAccount(serverReview.draft, context.clientAccountId);
        draft = serverReview.draft;
        review = serverReview;
        status = serverReview.readyToSubmit
            ? PurchaseRequestComposerStatus.reviewReady
            : PurchaseRequestComposerStatus.ready;
        if (!serverReview.readyToSubmit) {
          message = 'El servidor requiere: ${serverReview.missing.join(', ')}.';
        }
        notifyListeners();
        return;
      }
      status = PurchaseRequestComposerStatus.ready;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      status = PurchaseRequestComposerStatus.unavailable;
      if (!recoveryStateChecked ||
          recoveryStatus == PurchaseRequestRecoveryStatus.checking) {
        recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
      }
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = PurchaseRequestComposerStatus.unavailable;
      recoveryStatus = recoveryStateChecked
          ? PurchaseRequestRecoveryStatus.none
          : PurchaseRequestRecoveryStatus.unavailable;
      message = recoveryStateChecked
          ? 'No se pudieron cargar los datos para esta solicitud.'
          : 'No se pudo comprobar la recuperación local. No se iniciará otra solicitud.';
    }
    notifyListeners();
  }

  void selectAddress(String? addressId) {
    if (!activeAddresses.any((address) => address.id == addressId)) return;
    selectedAddressId = addressId;
    invalidateReview();
  }

  void selectPaymentPreference(String? value) {
    const choices = {
      'CREDIT_LINE',
      'BANK_TRANSFER',
      'CARD_STRIPE',
      'CASH',
      'CASH_ON_DELIVERY',
    };
    if (value == null || !choices.contains(value)) return;
    paymentPreference = value;
    invalidateReview();
  }

  void selectDeliveryDate(DateTime value) {
    if (value.isBefore(minimumDeliveryDate) ||
        value.weekday >= DateTime.saturday) {
      return;
    }
    requestedDeliveryDate = DateTime(value.year, value.month, value.day);
    invalidateReview();
  }

  void invalidateReview() {
    review = null;
    if (draft != null && status == PurchaseRequestComposerStatus.reviewReady) {
      status = PurchaseRequestComposerStatus.ready;
    }
    notifyListeners();
  }

  Future<void> prepare(String quantityText) async {
    final lease = _leaseKey;
    if (lease == null || !canCreateRequest || !canPrepareRequest) return;
    if (status == PurchaseRequestComposerStatus.preparing ||
        status == PurchaseRequestComposerStatus.submitting) {
      return;
    }
    final product = item;
    final context = purchaseContext;
    final skuId = product?.sellableSkuId;
    final account = context?.clientAccountId;
    final addressId = selectedAddressId;
    final quantity = num.tryParse(quantityText.trim());
    if (product == null ||
        context == null ||
        !_isUuid(skuId) ||
        !_isUuid(account) ||
        !_isUuid(addressId) ||
        quantity == null ||
        !quantity.isFinite ||
        quantity <= 0) {
      message = 'Revisa la cantidad y la dirección de entrega.';
      notifyListeners();
      return;
    }
    final sellableSkuId = skuId!;
    final clientAccountId = account!;
    final deliveryAddressId = addressId!;
    final scopeKey = _access.snapshot.currentContext!.scopeKey;
    final intentKey = _creationIntentKey(scopeKey, sellableSkuId);
    final generation = ++_requestGeneration;
    status = PurchaseRequestComposerStatus.preparing;
    message = null;
    notifyListeners();
    try {
      var current = draft;
      if (current == null) {
        try {
          await _repository.beginCreation(intentKey);
        } catch (_) {
          if (!_isCurrent(generation, lease)) return;
          recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
          status = PurchaseRequestComposerStatus.ready;
          message = 'No se pudo guardar la protección local. No se envió la solicitud; libera espacio y vuelve a intentar.';
          notifyListeners();
          return;
        }
        if (!_isCurrent(generation, lease)) return;
        recoveryStatus = PurchaseRequestRecoveryStatus.checking;
        try {
          current = await _repository.createDraft(
            clientAccountId: clientAccountId,
            requestedDeliveryDate: _dateValue(requestedDeliveryDate),
          );
        } on NexaApiFailure catch (failure) {
          if (failure.retryable || failure.statusCode == null) {
            await _refreshRecoveryCandidates(generation, lease);
          } else {
            await _clearCreationRecordAfterDefinitiveFailure(
              intentKey,
              generation,
              lease,
            );
          }
          rethrow;
        } catch (_) {
          await _refreshRecoveryCandidates(generation, lease);
          rethrow;
        }
        draft = current;
        try {
          await _repository.recordCreatedDraft(intentKey, current.id);
        } catch (_) {
          if (!_isCurrent(generation, lease)) return;
          recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
          status = PurchaseRequestComposerStatus.ready;
          message = 'El borrador está abierto en el servidor, pero no se pudo guardar su recuperación local. No inicies otro borrador.';
          notifyListeners();
          return;
        }
        recoveryStatus = PurchaseRequestRecoveryStatus.none;
        if (!_isCurrent(generation, lease)) return;
        if (current.clientAccountId != account) {
          throw const NexaApiFailure(
            code: 'draft_account_mismatch',
            userMessage: 'La solicitud pertenece a una cuenta distinta. Actualiza el contexto Buyer.',
          );
        }
        draft = current;
      } else {
        current = await _repository.getDraft(current.id);
        if (!_isCurrent(generation, lease)) return;
        if (current.clientAccountId != clientAccountId) {
          throw const NexaApiFailure(
            code: 'draft_account_mismatch',
            userMessage: 'La solicitud pertenece a una cuenta distinta. Actualiza el contexto Buyer.',
          );
        }
        draft = current;
      }

      if (current.status == 'SUBMITTED') {
        status = PurchaseRequestComposerStatus.submitted;
        draft = current;
        notifyListeners();
        return;
      }

      if (!_hasRequestedLine(
        current,
        sellableSkuId,
        quantity,
        product.unitOfMeasure,
      )) {
        current = await _repository.replaceLine(
          draft: current,
          sellableSkuId: sellableSkuId,
          quantity: quantity,
          unit: product.unitOfMeasure?.trim().isNotEmpty == true
              ? product.unitOfMeasure!.trim()
              : 'UNIT',
        );
        if (!_isCurrent(generation, lease)) return;
        draft = current;
      }
      if (current.destinationAddressId != deliveryAddressId) {
        current = await _repository.setDestination(
          draft: current,
          addressId: deliveryAddressId,
        );
        if (!_isCurrent(generation, lease)) return;
        draft = current;
      }
      if (current.routeProvider == null || !current.hasWarehouseSelection) {
        current = await _repository.previewRoute(current);
        if (!_isCurrent(generation, lease)) return;
        draft = current;
      }
      if (current.paymentPreference != paymentPreference ||
          current.requestedDeliveryDate != _dateValue(requestedDeliveryDate)) {
        current = await _repository.setPreferences(
          draft: current,
          paymentPreference: paymentPreference,
          requestedDeliveryDate: _dateValue(requestedDeliveryDate),
        );
        if (!_isCurrent(generation, lease)) return;
        draft = current;
      }
      final serverReview = await _repository.review(current.id);
      if (!_isCurrent(generation, lease)) return;
      if (serverReview.draft.clientAccountId != clientAccountId) {
        throw const NexaApiFailure(
          code: 'draft_account_mismatch',
          userMessage: 'La solicitud pertenece a una cuenta distinta. Actualiza el contexto Buyer.',
        );
      }
      draft = serverReview.draft;
      review = serverReview;
      status = serverReview.readyToSubmit
          ? PurchaseRequestComposerStatus.reviewReady
          : PurchaseRequestComposerStatus.ready;
      if (!serverReview.readyToSubmit) {
        message = 'El servidor requiere: ${serverReview.missing.join(', ')}.';
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      status =
          draft != null ||
              recoveryStatus == PurchaseRequestRecoveryStatus.reviewRequired
          ? PurchaseRequestComposerStatus.ready
          : PurchaseRequestComposerStatus.unavailable;
      message = recoveryStatus == PurchaseRequestRecoveryStatus.reviewRequired
          ? _uncertainCreationMessage
          : failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status =
          draft != null ||
              recoveryStatus == PurchaseRequestRecoveryStatus.reviewRequired
          ? PurchaseRequestComposerStatus.ready
          : PurchaseRequestComposerStatus.unavailable;
      message = recoveryStatus == PurchaseRequestRecoveryStatus.reviewRequired
          ? _uncertainCreationMessage
          : 'No se pudo completar la solicitud. Actualiza y vuelve a intentarlo.';
    }
    notifyListeners();
  }

  Future<void> submit() async {
    final lease = _leaseKey;
    final existing = draft;
    final accountId = purchaseContext?.clientAccountId;
    final scopeKey = _access.snapshot.currentContext?.scopeKey;
    if (lease == null ||
        existing == null ||
        accountId == null ||
        scopeKey == null ||
        !canCreateRequest ||
        status != PurchaseRequestComposerStatus.reviewReady) {
      return;
    }
    final generation = ++_requestGeneration;
    status = PurchaseRequestComposerStatus.submitting;
    message = null;
    notifyListeners();
    try {
      var latest = await _repository.getDraft(existing.id);
      if (!_isCurrent(generation, lease)) return;
      if (latest.clientAccountId != accountId) {
        throw const NexaApiFailure(
          code: 'draft_account_mismatch',
          userMessage: 'La solicitud pertenece a una cuenta distinta. Actualiza el contexto Buyer.',
        );
      }
      if (latest.status == 'SUBMITTED') {
        draft = latest;
        status = PurchaseRequestComposerStatus.submitted;
        await _clearCompletedCreationRecord(
          _creationIntentKey(scopeKey, item!.sellableSkuId!),
        );
        if (!_isCurrent(generation, lease)) return;
        notifyListeners();
        return;
      }
      final serverReview = await _repository.review(latest.id);
      if (!_isCurrent(generation, lease)) return;
      latest = serverReview.draft;
      if (!serverReview.readyToSubmit) {
        draft = latest;
        review = serverReview;
        status = PurchaseRequestComposerStatus.ready;
        message = 'El servidor requiere: ${serverReview.missing.join(', ')}.';
        notifyListeners();
        return;
      }
      final submitted = await _repository.submit(latest, scopeKey: scopeKey);
      if (!_isCurrent(generation, lease)) return;
      draft = submitted;
      status = submitted.status == 'SUBMITTED'
          ? PurchaseRequestComposerStatus.submitted
          : PurchaseRequestComposerStatus.ready;
      if (status == PurchaseRequestComposerStatus.submitted) {
        await _clearCompletedCreationRecord(
          _creationIntentKey(scopeKey, item!.sellableSkuId!),
        );
        if (!_isCurrent(generation, lease)) return;
      }
      if (status != PurchaseRequestComposerStatus.submitted) {
        message = 'El servidor no confirmó el envío. Actualiza el estado.';
      }
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      if (failure.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      status = PurchaseRequestComposerStatus.reviewReady;
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      status = PurchaseRequestComposerStatus.reviewReady;
      message = 'No se confirmó el envío. Puedes reintentar; se conservará la misma identidad de comando.';
    }
    notifyListeners();
  }

  Future<void> refreshRecovery({int page = 0}) async {
    final lease = _leaseKey;
    final scopeKey = _access.snapshot.currentContext?.scopeKey;
    if (lease == null || scopeKey == null || !canCreateRequest) return;
    final generation = ++_requestGeneration;
    recoveryStatus = PurchaseRequestRecoveryStatus.checking;
    inspectedRecoveryDraft = null;
    message = null;
    notifyListeners();
    try {
      final skuId = item?.sellableSkuId;
      if (skuId == null) throw const FormatException('SKU is missing.');
      final intentKey = _creationIntentKey(scopeKey, skuId);
      final record = await _repository.creationRecord(intentKey);
      if (!_isCurrent(generation, lease)) return;
      if (record == null) {
        recoveryStatus = PurchaseRequestRecoveryStatus.none;
        recoveryCandidates = const [];
        recoveryPage = 0;
        recoveryTotalPages = 1;
        status = PurchaseRequestComposerStatus.ready;
        message = null;
        notifyListeners();
        return;
      }
      if (!record.isUnknown) {
        final recovered = await _repository.getDraft(record.draftId!);
        if (!_isCurrent(generation, lease)) return;
        _requireSameBuyerAccount(recovered, purchaseContext!.clientAccountId);
        inspectedRecoveryDraft = recovered;
        if (_canResumeForCurrentItem(recovered, item!)) {
          recoveryCandidates = [
            PurchaseRequestDraftSummaryProjection(
              id: recovered.id,
              status: recovered.status,
              version: recovered.version,
              lineCount: recovered.lines.length,
              requestedDeliveryDate: recovered.requestedDeliveryDate,
            ),
          ];
          recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
          recoveryPage = 0;
          recoveryTotalPages = 1;
          status = PurchaseRequestComposerStatus.ready;
          message = _uncertainCreationMessage;
          notifyListeners();
          return;
        }
      }
      final pageResult = await _repository.listDrafts(page: page);
      if (!_isCurrent(generation, lease)) return;
      recoveryCandidates = pageResult.items;
      recoveryPage = pageResult.page;
      recoveryTotalPages = pageResult.totalPages;
      recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
      status = PurchaseRequestComposerStatus.ready;
      message = _uncertainCreationMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
      status = PurchaseRequestComposerStatus.unavailable;
      message = 'No se pudo consultar la recuperación. La nueva solicitud permanece bloqueada.';
    }
    notifyListeners();
  }

  Future<void> resumeDraft(String draftId) async {
    final lease = _leaseKey;
    final scopeKey = _access.snapshot.currentContext?.scopeKey;
    final accountId = purchaseContext?.clientAccountId;
    final currentItem = item;
    if (lease == null ||
        scopeKey == null ||
        accountId == null ||
        currentItem == null ||
        recoveryStatus != PurchaseRequestRecoveryStatus.reviewRequired ||
        !recoveryCandidates.any((candidate) => candidate.id == draftId)) {
      return;
    }
    final generation = ++_requestGeneration;
    recoveryStatus = PurchaseRequestRecoveryStatus.checking;
    inspectedRecoveryDraft = null;
    message = null;
    notifyListeners();
    try {
      final recovered = await _repository.getDraft(draftId);
      if (!_isCurrent(generation, lease)) return;
      if (recovered.id != draftId) {
        throw const NexaApiFailure(
          code: 'draft_id_mismatch',
          userMessage: 'El servidor respondió con otro borrador.',
        );
      }
      _requireSameBuyerAccount(recovered, accountId);
      final listed = recoveryCandidates.singleWhere(
        (candidate) => candidate.id == draftId,
      );
      if (recovered.version != listed.version ||
          recovered.status != listed.status) {
        recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
        message = 'El borrador cambió desde que se cargó la lista. Actualiza y revísalo de nuevo.';
        notifyListeners();
        return;
      }
      inspectedRecoveryDraft = recovered;
      if (!_canResumeForCurrentItem(recovered, currentItem)) {
        recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
        message = _knownDraftMismatchMessage;
        notifyListeners();
        return;
      }
      draft = recovered;
      if (recovered.status == 'SUBMITTED') {
        await _repository.recordCreatedDraft(
          _creationIntentKey(scopeKey, currentItem.sellableSkuId!),
          recovered.id,
        );
        if (!_isCurrent(generation, lease)) return;
        recoveryCandidates = const [];
        recoveryStatus = PurchaseRequestRecoveryStatus.none;
        status = PurchaseRequestComposerStatus.submitted;
        await _clearCompletedCreationRecord(
          _creationIntentKey(scopeKey, currentItem.sellableSkuId!),
        );
      } else {
        final serverReview = await _repository.review(recovered.id);
        if (!_isCurrent(generation, lease)) return;
        _requireSameBuyerAccount(serverReview.draft, accountId);
        if (serverReview.draft.version != recovered.version) {
          recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
          message = 'El borrador cambió durante la revisión. Actualiza y revísalo de nuevo.';
          notifyListeners();
          return;
        }
        await _repository.recordCreatedDraft(
          _creationIntentKey(scopeKey, currentItem.sellableSkuId!),
          recovered.id,
        );
        if (!_isCurrent(generation, lease)) return;
        recoveryCandidates = const [];
        recoveryStatus = PurchaseRequestRecoveryStatus.none;
        draft = serverReview.draft;
        review = serverReview;
        status = serverReview.readyToSubmit
            ? PurchaseRequestComposerStatus.reviewReady
            : PurchaseRequestComposerStatus.ready;
        if (!serverReview.readyToSubmit) {
          message = 'El servidor requiere: ${serverReview.missing.join(', ')}.';
        }
      }
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
      status = PurchaseRequestComposerStatus.ready;
      message = 'No se pudo retomar el borrador. La nueva solicitud permanece bloqueada.';
    }
    notifyListeners();
  }

  Future<void> startNewRequestAfterReview() async {
    final lease = _leaseKey;
    final scopeKey = _access.snapshot.currentContext?.scopeKey;
    if (lease == null ||
        scopeKey == null ||
        recoveryStatus != PurchaseRequestRecoveryStatus.reviewRequired) {
      return;
    }
    final generation = ++_requestGeneration;
    recoveryStatus = PurchaseRequestRecoveryStatus.checking;
    notifyListeners();
    try {
      final skuId = item?.sellableSkuId;
      if (skuId == null) throw const FormatException('SKU is missing.');
      await _repository.clearCreationRecord(
        _creationIntentKey(scopeKey, skuId),
      );
      if (!_isCurrent(generation, lease)) return;
      recoveryStatus = PurchaseRequestRecoveryStatus.none;
      recoveryCandidates = const [];
      inspectedRecoveryDraft = null;
      draft = null;
      review = null;
      status = PurchaseRequestComposerStatus.ready;
      message = 'La solicitud anterior aún podría aparecer después. Prepara otra solo si esa incertidumbre es aceptable.';
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
      status = PurchaseRequestComposerStatus.ready;
      message = 'No se pudo resolver la protección local. La nueva solicitud permanece bloqueada.';
    }
    notifyListeners();
  }

  Future<void> _refreshRecoveryCandidates(int generation, String lease) async {
    if (!_isCurrent(generation, lease)) return;
    recoveryStatus = PurchaseRequestRecoveryStatus.checking;
    notifyListeners();
    try {
      final page = await _repository.listDrafts(page: 0);
      if (!_isCurrent(generation, lease)) return;
      recoveryCandidates = page.items;
      recoveryPage = page.page;
      recoveryTotalPages = page.totalPages;
      recoveryStatus = PurchaseRequestRecoveryStatus.reviewRequired;
      status = PurchaseRequestComposerStatus.ready;
      message = _uncertainCreationMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      recoveryCandidates = const [];
      recoveryPage = 0;
      recoveryTotalPages = 1;
      recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
      status = PurchaseRequestComposerStatus.unavailable;
      message = 'No se pudo consultar la recuperación. La nueva solicitud permanece bloqueada.';
    }
    notifyListeners();
  }

  Future<void> _clearCreationRecordAfterDefinitiveFailure(
    String scopeKey,
    int generation,
    String lease,
  ) async {
    try {
      await _repository.clearCreationRecord(scopeKey);
      if (!_isCurrent(generation, lease)) return;
      recoveryStatus = PurchaseRequestRecoveryStatus.none;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      recoveryStatus = PurchaseRequestRecoveryStatus.unavailable;
      message = 'No se pudo confirmar el estado local. La nueva solicitud permanece bloqueada.';
    }
  }

  Future<void> _clearCompletedCreationRecord(String scopeKey) async {
    try {
      await _repository.clearCreationRecord(scopeKey);
    } catch (_) {
      // The server has already confirmed SUBMITTED. A retained marker is safe:
      // the next load will fetch that known draft and retry this cleanup.
    }
  }

  void _requireSameBuyerAccount(
    PurchaseRequestDraftProjection value,
    String accountId,
  ) {
    if (value.clientAccountId != accountId) {
      throw const NexaApiFailure(
        code: 'draft_account_mismatch',
        userMessage: 'La solicitud pertenece a una cuenta distinta. Actualiza el contexto Buyer.',
      );
    }
  }

  bool _canResumeForCurrentItem(
    PurchaseRequestDraftProjection value,
    CatalogItemProjection currentItem,
  ) {
    if (value.lines.isEmpty) return true;
    final skuId = currentItem.sellableSkuId;
    return value.lines.length == 1 &&
        skuId != null &&
        value.lines.single.skuId == skuId;
  }

  bool _hasRequestedLine(
    PurchaseRequestDraftProjection value,
    String skuId,
    num quantity,
    String? unit,
  ) =>
      value.lines.length == 1 &&
      value.lines.single.skuId == skuId &&
      num.tryParse(value.lines.single.quantity) == quantity &&
      value.lines.single.unit ==
          (unit?.trim().isNotEmpty == true ? unit!.trim() : 'UNIT');

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration && lease == _leaseKey;

  bool _hasPermission(String permission) =>
      _access.snapshot.currentContext?.permissions.contains(permission) == true;

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = _activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    item = null;
    purchaseContext = null;
    draft = null;
    review = null;
    recoveryStatus = PurchaseRequestRecoveryStatus.none;
    recoveryCandidates = const [];
    inspectedRecoveryDraft = null;
    recoveryPage = 0;
    recoveryTotalPages = 1;
    selectedAddressId = null;
    status = PurchaseRequestComposerStatus.unavailable;
    message = 'El contexto de acceso cambió. Vuelve a cargar la solicitud.';
    notifyListeners();
  }

  void _closeForChangedAccess() {
    item = null;
    purchaseContext = null;
    draft = null;
    review = null;
    recoveryStatus = PurchaseRequestRecoveryStatus.none;
    recoveryCandidates = const [];
    inspectedRecoveryDraft = null;
    recoveryPage = 0;
    recoveryTotalPages = 1;
    status = PurchaseRequestComposerStatus.unavailable;
    message = 'Inicia sesión para continuar.';
    notifyListeners();
  }

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  static bool _isUuid(String? value) =>
      value != null &&
      RegExp(
        r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
      ).hasMatch(value);

  static String _dateValue(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-'
      '${value.month.toString().padLeft(2, '0')}-'
      '${value.day.toString().padLeft(2, '0')}';

  static String _creationIntentKey(String scopeKey, String sellableSkuId) =>
      '$scopeKey|purchase-request-create|$sellableSkuId';

  static const _uncertainCreationMessage =
      'No se confirmó la respuesta de la creación. Revisa los borradores: una lista vacía no descarta que el servidor confirme la solicitud después.';
  static const _knownDraftMismatchMessage =
      'El borrador no coincide con este producto. Revísalo desde la lista de borradores antes de iniciar otra solicitud.';

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}

DateTime nextBusinessDate(int businessDays) {
  var candidate = DateTime.now().toUtc().add(Duration(days: businessDays));
  while (candidate.weekday >= DateTime.saturday) {
    candidate = candidate.add(const Duration(days: 1));
  }
  return DateTime(candidate.year, candidate.month, candidate.day);
}
