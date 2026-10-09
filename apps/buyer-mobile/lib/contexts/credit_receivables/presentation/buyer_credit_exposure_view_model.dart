import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/credit_exposure_repository.dart';

enum BuyerCreditExposureStatus { idle, loading, current, unavailable }

final class BuyerCreditExposureViewModel extends ChangeNotifier {
  BuyerCreditExposureViewModel(this._repository, this._access)
    : _leaseKey = _activeLease(_access.snapshot) {
    _subscription = _access.changes.listen(_onAccessChanged);
  }

  final BuyerCreditExposureRepository _repository;
  final BuyerAccessRepository _access;
  late final StreamSubscription<BuyerAccessSnapshot> _subscription;
  String? _leaseKey;
  int _requestGeneration = 0;

  BuyerCreditExposureStatus status = BuyerCreditExposureStatus.idle;
  BuyerCreditExposureProjection? exposure;
  String? message;

  Future<void> refresh() async {
    final lease = _leaseKey;
    if (lease == null) return;
    final generation = ++_requestGeneration;
    status = BuyerCreditExposureStatus.loading;
    message = null;
    notifyListeners();
    try {
      final result = await _repository.readCurrentBuyerExposure(
        currency: 'PEN',
      );
      if (!_isCurrent(generation, lease)) return;
      exposure = result;
      status = BuyerCreditExposureStatus.current;
    } on NexaApiFailure catch (failure) {
      if (!_isCurrent(generation, lease)) return;
      exposure = null;
      status = BuyerCreditExposureStatus.unavailable;
      message = failure.userMessage;
    } catch (_) {
      if (!_isCurrent(generation, lease)) return;
      exposure = null;
      status = BuyerCreditExposureStatus.unavailable;
      message = 'Credit information could not be refreshed.';
    }
    notifyListeners();
  }

  bool _isCurrent(int generation, String lease) =>
      generation == _requestGeneration && lease == _leaseKey;

  void _onAccessChanged(BuyerAccessSnapshot snapshot) {
    final nextLease = _activeLease(snapshot);
    if (nextLease == _leaseKey) return;
    _leaseKey = nextLease;
    _requestGeneration++;
    status = BuyerCreditExposureStatus.idle;
    exposure = null;
    message = null;
    notifyListeners();
  }

  static String? _activeLease(BuyerAccessSnapshot snapshot) =>
      snapshot.isSignedIn
      ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
      : null;

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
