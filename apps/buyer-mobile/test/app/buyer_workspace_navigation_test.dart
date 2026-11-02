import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/app/buyer_workspace_navigation.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test('redirects are testable and have stable closed destinations', () {
    expect(
      BuyerWorkspaceNavigation.redirectLocation(
        const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut),
        '/catalog',
      ),
      '/sign-in',
    );
    expect(
      BuyerWorkspaceNavigation.redirectLocation(
        const BuyerAccessSnapshot(status: BuyerAccessStatus.choosingContext),
        '/catalog',
      ),
      '/choose-context',
    );

    final noBuyerScope = _snapshot(
      roles: const {'BOM'},
      permissions: const {'catalog.read'},
    );
    expect(
      BuyerWorkspaceNavigation.redirectLocation(noBuyerScope, '/catalog'),
      '/workspace-unavailable',
    );
    expect(
      BuyerWorkspaceNavigation.redirectLocation(
        noBuyerScope,
        '/workspace-unavailable',
      ),
      isNull,
    );
    expect(
      BuyerWorkspaceNavigation.redirectLocation(
        _snapshot(permissions: const {}),
        '/orders',
      ),
      '/home',
    );
  });

  test('no current scoped Buyer authority produces no destinations', () {
    expect(
      BuyerWorkspaceNavigation.destinations(
        const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut),
      ),
      isEmpty,
    );
    expect(
      BuyerWorkspaceNavigation.destinations(
        _snapshot(epoch: 0, permissions: const {'catalog.read'}),
      ),
      isEmpty,
    );
    expect(
      BuyerWorkspaceNavigation.destinations(
        _snapshot(workspaceId: '', permissions: const {'catalog.read'}),
      ),
      isEmpty,
    );
    expect(
      BuyerWorkspaceNavigation.destinations(
        _snapshot(userId: '', permissions: const {'catalog.read'}),
      ),
      isEmpty,
    );
    expect(
      BuyerWorkspaceNavigation.destinations(
        _snapshot(membershipId: '', permissions: const {'catalog.read'}),
      ),
      isEmpty,
    );
    expect(
      BuyerWorkspaceNavigation.destinations(
        _snapshot(roles: const {'BOM'}, permissions: const {'catalog.read'}),
      ),
      isEmpty,
    );
  });

  test(
    'Home is the scoped Buyer landing and feature tabs use server grants',
    () {
      final noFeatureGrants = _snapshot(permissions: const {});
      expect(BuyerWorkspaceNavigation.destinations(noFeatureGrants), const [
        BuyerWorkspaceDestination.home,
      ]);
      expect(BuyerWorkspaceNavigation.landingPath(noFeatureGrants), '/home');
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(noFeatureGrants, '/home'),
        isTrue,
      );

      final snapshot = _snapshot(
        permissions: const {
          'catalog.read',
          'buyer.order.read',
          'payment.read',
          'payment.create',
          'document.read',
          'buyer.tracking.read',
        },
      );

      final options = BuyerWorkspaceNavigation.destinations(snapshot);

      expect(options, const [
        BuyerWorkspaceDestination.home,
        BuyerWorkspaceDestination.catalog,
        BuyerWorkspaceDestination.orders,
        BuyerWorkspaceDestination.credit,
        BuyerWorkspaceDestination.documents,
        BuyerWorkspaceDestination.wallet,
        BuyerWorkspaceDestination.deliveries,
      ]);
      expect(options.toSet(), hasLength(options.length));
    },
  );

  test('bottom navigation hides 0 or 1 tab and selects visible branch', () {
    expect(
      BuyerWorkspaceNavigation.selectedBottomNavigationIndex(const [], 0),
      -1,
    );
    expect(
      BuyerWorkspaceNavigation.selectedBottomNavigationIndex(const [
        BuyerWorkspaceDestination.wallet,
      ], 4),
      -1,
    );
    expect(
      BuyerWorkspaceNavigation.selectedBottomNavigationIndex(const [
        BuyerWorkspaceDestination.wallet,
        BuyerWorkspaceDestination.deliveries,
      ], 6),
      1,
    );
    expect(
      BuyerWorkspaceNavigation.selectedBottomNavigationIndex(const [
        BuyerWorkspaceDestination.wallet,
        BuyerWorkspaceDestination.deliveries,
      ], 0),
      -1,
    );
  });

  test('role grants only Home; wallet still needs its endpoint grant', () {
    expect(
      BuyerWorkspaceNavigation.destinations(_snapshot(permissions: const {})),
      const [BuyerWorkspaceDestination.home],
    );

    final walletOnly = BuyerWorkspaceNavigation.destinations(
      _snapshot(permissions: const {'payment.create'}),
    );
    expect(walletOnly, const [
      BuyerWorkspaceDestination.home,
      BuyerWorkspaceDestination.wallet,
    ]);
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(
        _snapshot(permissions: const {'payment.create'}),
        '/wallet',
      ),
      isTrue,
    );
  });

  test(
    'direct routes fail closed unless their server permissions are present',
    () {
      final base = _snapshot(permissions: const {'catalog.read'});
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(base, '/catalog'),
        isTrue,
      );
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(base, '/orders'),
        isFalse,
      );
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(base, '/unknown'),
        isFalse,
      );
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(
          base,
          '/catalog/CAT-0001/purchase-request',
        ),
        isFalse,
      );

      final purchaseRequest = _snapshot(
        permissions: const {
          'catalog.read',
          'buyer.sales.read',
          'buyer.sales.write',
        },
      );
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(
          purchaseRequest,
          '/catalog/CAT-0001/purchase-request',
        ),
        isTrue,
      );
    },
  );

  test(
    'route policy reflects permission revocation in a new authority epoch',
    () {
      final catalogAuthority = _snapshot(
        epoch: 7,
        permissions: const {'catalog.read'},
      );
      final ordersAuthority = _snapshot(
        epoch: 8,
        permissions: const {'buyer.order.read'},
      );

      expect(
        BuyerWorkspaceNavigation.canOpenLocation(catalogAuthority, '/catalog'),
        isTrue,
      );
      expect(
        BuyerWorkspaceNavigation.canOpenLocation(ordersAuthority, '/catalog'),
        isFalse,
      );
      expect(
        BuyerWorkspaceNavigation.redirectLocation(ordersAuthority, '/catalog'),
        '/home',
      );
      expect(BuyerWorkspaceNavigation.landingPath(ordersAuthority), '/home');
    },
  );

  test('credit and notification affordances use endpoint permissions', () {
    final paymentCreateOnly = _snapshot(permissions: const {'payment.create'});
    expect(BuyerWorkspaceNavigation.destinations(paymentCreateOnly), const [
      BuyerWorkspaceDestination.home,
      BuyerWorkspaceDestination.wallet,
    ]);
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(paymentCreateOnly, '/credit'),
      isFalse,
    );
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(
        paymentCreateOnly,
        '/credit/receivables/abc/payments',
      ),
      isTrue,
    );
    expect(
      BuyerWorkspaceNavigation.canReadNotifications(paymentCreateOnly),
      isFalse,
    );

    final notificationReader = _snapshot(
      permissions: const {'notification.read'},
    );
    expect(
      BuyerWorkspaceNavigation.canReadNotifications(notificationReader),
      isTrue,
    );
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(
        notificationReader,
        '/notifications',
      ),
      isTrue,
    );
  });

  test('addresses and favorites require their read permissions', () {
    final salesReader = _snapshot(permissions: const {'buyer.sales.read'});
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(salesReader, '/addresses'),
      isTrue,
    );
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(salesReader, '/favorites'),
      isFalse,
    );

    final catalogAndSalesReader = _snapshot(
      permissions: const {'catalog.read', 'buyer.sales.read'},
    );
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(
        catalogAndSalesReader,
        '/favorites',
      ),
      isTrue,
    );
    expect(
      BuyerWorkspaceNavigation.canOpenLocation(
        _snapshot(permissions: const {'catalog.read'}),
        '/addresses',
      ),
      isFalse,
    );
  });
}

BuyerAccessSnapshot _snapshot({
  Set<String> roles = const {'BUYER'},
  Set<String> permissions = const {},
  int epoch = 1,
  String userId = 'user-1',
  String membershipId = 'membership-1',
  String workspaceId = 'workspace-1',
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    userId: userId,
    membershipId: membershipId,
    tenantId: 'tenant-1',
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: workspaceId,
    workspaceName: 'Main',
    workspaceSlug: 'main',
    roles: roles,
    permissions: permissions,
  ),
);
