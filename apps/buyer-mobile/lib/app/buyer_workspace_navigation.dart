import '../contexts/tenant_access_governance/application/buyer_access_repository.dart';

enum BuyerWorkspaceDestination {
  catalog('/catalog', 'Catálogo', 0),
  orders('/orders', 'Mis pedidos', 1),
  credit('/credit', 'Crédito y pagos', 2),
  documents('/documents', 'Documentos', 3),
  wallet('/wallet', 'Billetera', 4),
  deliveries('/deliveries', 'Mis entregas', 5),
  requests('/requests', 'Solicitudes', 6);

  const BuyerWorkspaceDestination(this.path, this.label, this.branchIndex);

  final String path;
  final String label;
  final int branchIndex;
}

/// Projects the current server-granted Buyer authority into shell destinations.
/// Role membership only constrains the Buyer surface and wallet endpoint; it
/// never grants a destination without that destination's server permission.
abstract final class BuyerWorkspaceNavigation {
  static const _buyerRole = 'BUYER';
  static const _catalogRead = 'catalog.read';
  static const _buyerOrderRead = 'buyer.order.read';
  static const _paymentRead = 'payment.read';
  static const _paymentCreate = 'payment.create';
  static const _documentRead = 'document.read';
  static const _buyerTrackingRead = 'buyer.tracking.read';
  static const _notificationRead = 'notification.read';
  static const _notificationManagePreferences =
      'notification.manage_preferences';
  static const _buyerSalesRead = 'buyer.sales.read';
  static const _buyerSalesWrite = 'buyer.sales.write';

  static List<BuyerWorkspaceDestination> destinations(
    BuyerAccessSnapshot snapshot,
  ) {
    final context = _currentBuyerContext(snapshot);
    if (context == null) return const [];

    final permissions = context.permissions;
    final options = <BuyerWorkspaceDestination>[
      if (permissions.contains(_catalogRead)) BuyerWorkspaceDestination.catalog,
      if (permissions.contains(_buyerOrderRead))
        BuyerWorkspaceDestination.orders,
      if (permissions.contains(_paymentRead)) BuyerWorkspaceDestination.credit,
      if (permissions.contains(_documentRead))
        BuyerWorkspaceDestination.documents,
      if (context.roles.contains(_buyerRole) &&
          (permissions.contains(_paymentRead) ||
              permissions.contains(_paymentCreate)))
        BuyerWorkspaceDestination.wallet,
      if (permissions.contains(_buyerTrackingRead))
        BuyerWorkspaceDestination.deliveries,
      if (permissions.contains(_buyerSalesRead))
        BuyerWorkspaceDestination.requests,
    ];

    // Destination definitions are static, but keep the projection's invariant
    // explicit so a future merged capability source cannot duplicate a tab.
    return List.unmodifiable(options.toSet());
  }

  static bool canReadNotifications(BuyerAccessSnapshot snapshot) =>
      _hasPermission(snapshot, _notificationRead);

  static bool canManageNotificationPreferences(BuyerAccessSnapshot snapshot) =>
      _hasPermission(snapshot, _notificationManagePreferences);

  static String landingPath(BuyerAccessSnapshot snapshot) {
    final available = destinations(snapshot);
    return available.isEmpty ? '/workspace-unavailable' : available.first.path;
  }

  static String? redirectLocation(BuyerAccessSnapshot snapshot, String path) {
    if (path == '/forgot-password' || path == '/reset-password') return null;
    switch (snapshot.status) {
      case BuyerAccessStatus.signedOut:
        return path == '/sign-in' ? null : '/sign-in';
      case BuyerAccessStatus.choosingContext:
        return path == '/choose-context' ? null : '/choose-context';
      case BuyerAccessStatus.signedIn:
        if (!hasCurrentBuyerScope(snapshot)) {
          return path == '/workspace-unavailable'
              ? null
              : '/workspace-unavailable';
        }
        if (path == '/sign-in' || path == '/choose-context') {
          return landingPath(snapshot);
        }
        final landing = landingPath(snapshot);
        if (path == '/workspace-unavailable') {
          return landing == path ? null : landing;
        }
        return canOpenLocation(snapshot, path) ? null : landing;
    }
  }

  /// NavigationBar requires at least two destinations. Return -1 when current
  /// branch has no visible tab or when projection contains only one tab.
  static int selectedBottomNavigationIndex(
    List<BuyerWorkspaceDestination> destinations,
    int currentBranchIndex,
  ) {
    if (destinations.length < 2) return -1;
    return destinations.indexWhere(
      (destination) => destination.branchIndex == currentBranchIndex,
    );
  }

  /// Route policy mirrors the server capability needed by each Buyer screen.
  /// Unknown or out-of-scope locations are denied.
  static bool canOpenLocation(BuyerAccessSnapshot snapshot, String path) {
    final context = _currentBuyerContext(snapshot);
    if (context == null) return false;
    final permissions = context.permissions;

    if (_isWithin(path, '/notifications/preferences')) {
      return permissions.contains(_notificationRead);
    }
    if (_isWithin(path, '/notifications')) {
      return permissions.contains(_notificationRead);
    }
    if (_isWithin(path, '/catalog')) {
      if (path.startsWith('/catalog/') && path.endsWith('/purchase-request')) {
        return permissions.contains(_catalogRead) &&
            permissions.contains(_buyerSalesRead) &&
            permissions.contains(_buyerSalesWrite);
      }
      return permissions.contains(_catalogRead);
    }
    if (_isWithin(path, '/orders')) {
      return permissions.contains(_buyerOrderRead);
    }
    if (_isWithin(path, '/requests')) {
      return permissions.contains(_buyerSalesRead);
    }
    if (_isWithin(path, '/credit')) {
      if (path.contains('/receivables/') && path.endsWith('/payments')) {
        return permissions.contains(_paymentRead) ||
            permissions.contains(_paymentCreate);
      }
      return permissions.contains(_paymentRead);
    }
    if (_isWithin(path, '/documents')) {
      return permissions.contains(_documentRead);
    }
    if (_isWithin(path, '/wallet')) {
      return context.roles.contains(_buyerRole) &&
          (permissions.contains(_paymentRead) ||
              permissions.contains(_paymentCreate));
    }
    if (_isWithin(path, '/deliveries')) {
      return permissions.contains(_buyerTrackingRead);
    }
    return false;
  }

  static bool hasCurrentBuyerScope(BuyerAccessSnapshot snapshot) =>
      _currentBuyerContext(snapshot) != null;

  static BuyerAccessContext? _currentBuyerContext(
    BuyerAccessSnapshot snapshot,
  ) {
    return snapshot.hasActiveBuyerScope ? snapshot.currentContext : null;
  }

  static bool _hasPermission(BuyerAccessSnapshot snapshot, String permission) {
    final context = _currentBuyerContext(snapshot);
    return context?.permissions.contains(permission) ?? false;
  }

  static bool _isWithin(String path, String prefix) =>
      path == prefix || path.startsWith('$prefix/');
}
