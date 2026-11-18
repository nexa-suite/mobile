import '../contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'buyer_workspace_navigation.dart';

/// Keeps the terms gate ahead of business routes and resumes only safe paths.
final class BuyerMobileTermsNavigation {
  String? _sessionKey;
  String? _pendingReturnPath;

  String? redirectToTermsIfRequired({
    required BuyerAccessSnapshot snapshot,
    required String path,
    required bool acceptedForCurrentSession,
  }) {
    final nextSessionKey = _sessionKeyFor(snapshot);
    if (nextSessionKey != _sessionKey) {
      _sessionKey = nextSessionKey;
      _pendingReturnPath = null;
    }
    if (!snapshot.hasActiveBuyerScope) return null;
    if (path == '/legal/terms') {
      return acceptedForCurrentSession && _pendingReturnPath != null
          ? continueAfterAcceptance(snapshot)
          : null;
    }
    if (acceptedForCurrentSession) return null;
    _pendingReturnPath =
        BuyerWorkspaceNavigation.canOpenLocation(snapshot, path)
        ? path
        : BuyerWorkspaceNavigation.landingPath(snapshot);
    return '/legal/terms';
  }

  String continueAfterAcceptance(BuyerAccessSnapshot snapshot) {
    final pendingPath = _pendingReturnPath;
    _pendingReturnPath = null;
    if (pendingPath != null &&
        snapshot.hasActiveBuyerScope &&
        BuyerWorkspaceNavigation.canOpenLocation(snapshot, pendingPath)) {
      return pendingPath;
    }
    return BuyerWorkspaceNavigation.landingPath(snapshot);
  }

  static String? _sessionKeyFor(BuyerAccessSnapshot snapshot) {
    if (!snapshot.hasActiveBuyerScope) return null;
    final context = snapshot.currentContext!;
    return '${snapshot.authorityEpoch}|${context.userId}|'
        '${context.authorityFingerprint}';
  }
}
