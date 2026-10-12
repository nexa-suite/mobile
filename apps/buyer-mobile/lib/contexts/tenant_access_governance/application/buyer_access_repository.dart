enum BuyerAccessStatus { signedOut, choosingContext, signedIn }

final class BuyerAccessContext {
  const BuyerAccessContext({
    required this.membershipId,
    required this.tenantId,
    required this.tenantName,
    required this.tenantSlug,
    required this.workspaceId,
    required this.workspaceName,
    required this.workspaceSlug,
    this.userId,
    this.roles = const {},
    this.permissions = const {},
    this.authorizationVersion,
    this.displayName,
    this.email,
  });

  final String membershipId;
  final String tenantId;
  final String tenantName;
  final String tenantSlug;
  final String workspaceId;
  final String workspaceName;
  final String workspaceSlug;
  final String? userId;
  final Set<String> roles;
  final Set<String> permissions;
  final int? authorizationVersion;
  final String? displayName;
  final String? email;

  String get scopeKey => '$membershipId|$tenantId|$workspaceId';

  String get authorityFingerprint {
    final sortedRoles = roles.toList()..sort();
    final sortedPermissions = permissions.toList()..sort();
    return '${userId ?? ''}|$scopeKey|${authorizationVersion ?? ''}|'
        '${sortedRoles.join(',')}|${sortedPermissions.join(',')}';
  }
}

final class BuyerAccessSnapshot {
  const BuyerAccessSnapshot({
    required this.status,
    this.authorityEpoch = 0,
    this.currentContext,
    this.availableContexts = const [],
  });

  final BuyerAccessStatus status;
  final int authorityEpoch;
  final BuyerAccessContext? currentContext;
  final List<BuyerAccessContext> availableContexts;

  bool get isSignedIn =>
      status == BuyerAccessStatus.signedIn && currentContext != null;
}

abstract interface class BuyerAccessRepository {
  BuyerAccessSnapshot get snapshot;

  Stream<BuyerAccessSnapshot> get changes;

  Future<void> signIn({required String identifier, required String password});

  Future<void> selectContext(String membershipId);

  Future<void> signOut();

  void invalidateLocalSession();

  Future<void> dispose();
}
