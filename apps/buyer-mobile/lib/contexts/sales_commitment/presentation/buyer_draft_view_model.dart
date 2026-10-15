import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/application/error/nexa_api_failure.dart';
import '../../catalog_commercial_policy/application/catalog_repository.dart';
import '../../payments/application/buyer_order_payment_capability_query.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_purchase_workspace_repository.dart';
import '../application/purchase_request_repository.dart';

final class BuyerDraftViewModel extends ChangeNotifier {
  BuyerDraftViewModel(
    this._repository,
    this._workspace,
    this._access,
    this._wallet,
    this.id,
  ) : _authority = _lease(_access.snapshot) {
    _subscription = _access.changes.listen((snapshot) {
      final next = _lease(snapshot);
      if (next == _authority) {
        return;
      }
      _authority = next;
      _generation++;
      draft = null;
      review = null;
      purchaseContext = null;
      addressId = null;
      paymentPreference = null;
      deliveryDate = null;
      walletSupported = false;
      mustReload = false;
      busy = false;
      message = 'El acceso cambió. Abre nuevamente el borrador.';
      notifyListeners();
    });
  }
  final PurchaseRequestRepository _repository;
  final BuyerPurchaseWorkspaceRepository _workspace;
  final BuyerAccessRepository _access;
  final BuyerOrderPaymentCapabilityQuery _wallet;
  final String id;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _authority;
  int _generation = 0;
  bool busy = false;
  bool mustReload = false;
  bool walletSupported = false;
  String? message;
  PurchaseRequestDraftProjection? draft;
  PurchaseRequestReviewProjection? review;
  BuyerPurchaseContextProjection? purchaseContext;
  String? addressId;
  String? paymentPreference;
  DateTime? deliveryDate;
  bool get canRead =>
      _authority != null &&
      _authority == _lease(_access.snapshot) &&
      _access.snapshot.hasActiveBuyerScope &&
      _access.snapshot.currentContext!.permissions.contains('buyer.sales.read');
  bool get canEdit =>
      canRead &&
      _access.snapshot.currentContext!.permissions.contains(
        'buyer.sales.write',
      ) &&
      isMutable &&
      !busy &&
      !mustReload;
  bool get isMutable => const {
    'DRAFT',
    'PRODUCTS_COMPLETE',
    'DESTINATION_COMPLETE',
    'ROUTE_VALIDATED',
    'COMMERCIAL_REVIEW_COMPLETE',
    'READY_TO_SUBMIT',
  }.contains(draft?.status);
  bool get canSubmit =>
      canEdit &&
      review?.readyToSubmit == true &&
      review?.draft.version == draft?.version &&
      review?.draft.id == draft?.id &&
      draft?.status == 'READY_TO_SUBMIT' &&
      (paymentPreference != 'WALLET' || canOfferWallet);
  bool get canOfferWallet =>
      walletSupported &&
      canRead &&
      _access.snapshot.currentContext!.permissions.contains('payment.read');

  Future<void> load() async {
    if (busy || _authority == null || !canRead) {
      return;
    }
    final authority = _authority;
    final generation = ++_generation;
    busy = true;
    message = null;
    review = null;
    walletSupported = false;
    notifyListeners();
    try {
      final account = await _repository.loadBuyerPurchaseContext();
      if (!_current(generation, authority)) {
        return;
      }
      if (account.buyerMembershipId !=
          _access.snapshot.currentContext!.membershipId) {
        throw const NexaApiFailure(
          code: 'buyer_relationship_mismatch',
          userMessage: 'La cuenta Buyer no coincide con tu acceso.',
        );
      }
      final loaded = await _repository.getDraft(id);
      if (!_current(generation, authority)) {
        return;
      }
      if (loaded.clientAccountId != account.clientAccountId) {
        throw const NexaApiFailure(
          code: 'buyer_account_mismatch',
          userMessage: 'Este borrador no corresponde a tu cuenta.',
        );
      }
      draft = loaded;
      purchaseContext = account;
      addressId = loaded.destinationAddressId;
      paymentPreference = loaded.paymentPreference;
      deliveryDate = DateTime.tryParse(loaded.requestedDeliveryDate ?? '');
      mustReload = false;
      if (loaded.status == 'READY_TO_SUBMIT') {
        final checked = await _repository.review(id);
        if (!_current(generation, authority)) {
          return;
        }
        draft = checked.draft;
        addressId = checked.draft.destinationAddressId;
        paymentPreference = checked.draft.paymentPreference;
        deliveryDate = DateTime.tryParse(
          checked.draft.requestedDeliveryDate ?? '',
        );
        review = checked;
      }
      if (_access.snapshot.currentContext!.permissions.contains(
        'payment.read',
      )) {
        try {
          final supported = await _wallet.isOrderPaymentSupported();
          if (!_current(generation, authority)) {
            return;
          }
          walletSupported = supported;
        } on NexaApiFailure catch (error) {
          if (!_current(generation, authority)) {
            return;
          }
          if (error.statusCode == 401) {
            rethrow;
          }
        } catch (_) {
          if (!_current(generation, authority)) {
            return;
          }
        }
      }
    } on NexaApiFailure catch (error) {
      if (!_current(generation, authority)) {
        return;
      }
      if (error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      draft = null;
      purchaseContext = null;
      message = error.userMessage;
    } catch (_) {
      if (!_current(generation, authority)) {
        return;
      }
      draft = null;
      purchaseContext = null;
      review = null;
      message = 'No se pudo abrir el borrador.';
    }
    if (!_current(generation, authority)) {
      return;
    }
    busy = false;
    notifyListeners();
  }

  void selectAddress(String? value) {
    if (!canEdit) {
      return;
    }
    addressId = value;
    _invalidate();
  }

  void selectPayment(String? value) {
    if (!canEdit) {
      return;
    }
    paymentPreference = value;
    _invalidate();
  }

  void selectDate(DateTime value) {
    if (!canEdit) {
      return;
    }
    deliveryDate = value;
    _invalidate();
  }

  void _invalidate() {
    review = null;
    message = null;
    notifyListeners();
  }

  Future<void> updateLine(
    PurchaseRequestLineProjection line,
    String quantity,
  ) async {
    if (!canEdit || draft == null) {
      return;
    }
    final parsed = num.tryParse(quantity.replaceAll(',', '.'));
    if (parsed == null || !parsed.isFinite || parsed <= 0) {
      message = 'Escribe una cantidad mayor que cero.';
      notifyListeners();
      return;
    }
    await _replace([
      for (final existing in draft!.lines)
        existing.skuId == line.skuId
            ? PurchaseRequestLineProjection(
                skuId: line.skuId,
                quantity: '$parsed',
                unit: line.unit,
                notes: line.notes,
              )
            : existing,
    ]);
  }

  Future<void> addProduct(CatalogItemProjection item, String quantity) async {
    if (!canEdit ||
        draft == null ||
        item.sellableSkuId == null ||
        !_access.snapshot.currentContext!.permissions.contains(
          'catalog.read',
        )) {
      return;
    }
    final parsed = num.tryParse(quantity.replaceAll(',', '.'));
    if (parsed == null || !parsed.isFinite || parsed <= 0) {
      message = 'Escribe una cantidad mayor que cero.';
      notifyListeners();
      return;
    }
    if (draft!.lines.any((line) => line.skuId == item.sellableSkuId)) {
      message = 'Este producto ya está en la solicitud. Edita su cantidad.';
      notifyListeners();
      return;
    }
    await _replace([
      ...draft!.lines,
      PurchaseRequestLineProjection(
        skuId: item.sellableSkuId!,
        quantity: '$parsed',
        unit: item.unitOfMeasure ?? 'UNIT',
      ),
    ]);
  }

  Future<void> removeLine(PurchaseRequestLineProjection line) async {
    if (!canEdit || draft == null) {
      return;
    }
    if (draft!.lines.length <= 1) {
      message = 'El borrador debe conservar al menos un producto.';
      notifyListeners();
      return;
    }
    await _replace(
      draft!.lines.where((value) => value.skuId != line.skuId).toList(),
    );
  }

  Future<void> _replace(List<PurchaseRequestLineProjection> lines) =>
      _run(() async {
        final generation = _generation;
        final authority = _authority;
        final result = await _workspace.replaceDraftLines(
          draft: draft!,
          lines: lines,
        );
        if (!_current(generation, authority)) {
          return;
        }
        draft = result;
        review = null;
      });

  Future<void> prepare() async {
    if (!canEdit) {
      return;
    }
    final selectedAddress = addressId;
    final selectedDate = deliveryDate;
    final selectedPayment = paymentPreference;
    if (selectedAddress == null ||
        selectedDate == null ||
        selectedPayment == null ||
        !(purchaseContext?.addresses.any(
              (address) => address.id == selectedAddress && address.active,
            ) ??
            false)) {
      message = 'Elige dirección activa, fecha y preferencia de pago.';
      notifyListeners();
      return;
    }
    if (selectedPayment == 'WALLET' && !canOfferWallet) {
      message = 'Billetera no está disponible. Selecciona otra preferencia.';
      notifyListeners();
      return;
    }
    await _run(() async {
      final generation = _generation;
      final authority = _authority;
      final destination = await _repository.setDestination(
        draft: draft!,
        addressId: selectedAddress,
      );
      if (!_current(generation, authority)) {
        return;
      }
      draft = destination;
      final route = await _repository.previewRoute(draft!);
      if (!_current(generation, authority)) {
        return;
      }
      draft = route;
      final date =
          '${selectedDate.year.toString().padLeft(4, '0')}-${selectedDate.month.toString().padLeft(2, '0')}-${selectedDate.day.toString().padLeft(2, '0')}';
      final preferences = await _repository.setPreferences(
        draft: draft!,
        paymentPreference: selectedPayment,
        requestedDeliveryDate: date,
      );
      if (!_current(generation, authority)) {
        return;
      }
      draft = preferences;
      final result = await _repository.review(id);
      if (!_current(generation, authority)) {
        return;
      }
      draft = result.draft;
      addressId = result.draft.destinationAddressId;
      paymentPreference = result.draft.paymentPreference;
      deliveryDate = DateTime.tryParse(
        result.draft.requestedDeliveryDate ?? '',
      );
      review = result;
      message = result.readyToSubmit
          ? 'Solicitud preparada. Revisa precios y productos antes de enviarla.'
          : 'Pendiente: ${result.missing.join(', ')}.';
    });
  }

  Future<void> submit() async {
    if (!canSubmit) {
      return;
    }
    final scope = _access.snapshot.currentContext!.scopeKey;
    await _run(() async {
      final generation = _generation;
      final authority = _authority;
      final result = await _repository.submit(draft!, scopeKey: scope);
      if (!_current(generation, authority)) {
        return;
      }
      draft = result;
      review = null;
      message = 'Nexa confirmó el envío de tu solicitud.';
    });
  }

  Future<void> _run(Future<void> Function() command) async {
    if (!canEdit) {
      return;
    }
    final generation = ++_generation;
    final authority = _authority;
    busy = true;
    message = null;
    review = null;
    notifyListeners();
    try {
      await command();
    } on NexaApiFailure catch (error) {
      if (!_current(generation, authority)) {
        return;
      }
      if (error.statusCode == 401) {
        _access.invalidateLocalSession();
        return;
      }
      mustReload = true;
      review = null;
      if (error.statusCode == 403) {
        draft = null;
        purchaseContext = null;
        addressId = null;
        paymentPreference = null;
        deliveryDate = null;
        walletSupported = false;
      }
      message = error.statusCode == null || error.statusCode! >= 500
          ? 'No se confirmó el resultado. Consulta el borrador antes de volver a continuar.'
          : error.userMessage;
    } catch (_) {
      if (!_current(generation, authority)) {
        return;
      }
      mustReload = true;
      review = null;
      message = 'No se confirmó el resultado. Consulta nuevamente el borrador.';
    }
    if (!_current(generation, authority)) {
      return;
    }
    busy = false;
    notifyListeners();
  }

  bool _current(int generation, String? authority) =>
      generation == _generation &&
      authority == _authority &&
      authority == _lease(_access.snapshot);
  static String? _lease(BuyerAccessSnapshot snapshot) =>
      snapshot.hasActiveBuyerScope
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;
  @override
  void dispose() {
    _generation++;
    _subscription.cancel();
    super.dispose();
  }
}
