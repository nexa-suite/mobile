import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/app/buyer_mobile_terms_navigation.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test('gates a granted business route and resumes it after acceptance', () {
    final navigation = BuyerMobileTermsNavigation();
    final snapshot = _snapshot(permissions: const {'notification.read'});

    expect(
      navigation.redirectToTermsIfRequired(
        snapshot: snapshot,
        path: '/notifications',
        acceptedForCurrentSession: false,
      ),
      '/legal/terms',
    );
    expect(
      navigation.redirectToTermsIfRequired(
        snapshot: snapshot,
        path: '/legal/terms',
        acceptedForCurrentSession: true,
      ),
      '/notifications',
    );
    expect(
      navigation.redirectToTermsIfRequired(
        snapshot: snapshot,
        path: '/legal/terms',
        acceptedForCurrentSession: true,
      ),
      isNull,
    );
  });

  test('drops pending business route when authority changes', () {
    final navigation = BuyerMobileTermsNavigation();
    final original = _snapshot(permissions: const {'notification.read'});
    navigation.redirectToTermsIfRequired(
      snapshot: original,
      path: '/notifications',
      acceptedForCurrentSession: false,
    );
    final changed = _snapshot(epoch: 2, permissions: const {});

    expect(
      navigation.redirectToTermsIfRequired(
        snapshot: changed,
        path: '/legal/terms',
        acceptedForCurrentSession: false,
      ),
      isNull,
    );
    expect(navigation.continueAfterAcceptance(changed), '/home');
  });

  test('never retains an unauthorized or query-bearing return location', () {
    final navigation = BuyerMobileTermsNavigation();
    final snapshot = _snapshot();
    navigation.redirectToTermsIfRequired(
      snapshot: snapshot,
      path: '/notifications?target=external',
      acceptedForCurrentSession: false,
    );

    expect(navigation.continueAfterAcceptance(snapshot), '/home');
  });
}

BuyerAccessSnapshot _snapshot({
  int epoch = 1,
  Set<String> permissions = const {},
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    membershipId: 'membership-1',
    tenantId: 'tenant-1',
    tenantName: 'Tenant',
    tenantSlug: 'tenant',
    workspaceId: 'workspace-1',
    workspaceName: 'Workspace',
    workspaceSlug: 'workspace',
    userId: 'user-1',
    roles: const {'BUYER'},
    permissions: permissions,
  ),
);
