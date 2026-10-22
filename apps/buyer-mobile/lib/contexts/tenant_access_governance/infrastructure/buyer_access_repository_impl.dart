import 'dart:async';

import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_access_repository.dart';
import 'platform_buyer_biometric_access.dart';

final class BuyerAccessRepositoryImpl
    implements BuyerAccessRepository, SessionCredentialProvider {
  BuyerAccessRepositoryImpl(this._api, {BuyerBiometricAccess? biometricAccess})
    : _biometricAccess = biometricAccess ?? PlatformBuyerBiometricAccess();

  final NexaApiClient _api;
  final BuyerBiometricAccess _biometricAccess;
  final StreamController<BuyerAccessSnapshot> _changes =
      StreamController<BuyerAccessSnapshot>.broadcast(sync: true);

  BuyerAccessSnapshot _snapshot = const BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedOut,
  );
  String? _accessToken;
  String? _refreshToken;
  String? _contextTicket;
  Future<String?>? _refreshInFlight;
  int? _refreshAuthorityEpoch;
  String? _refreshTokenUsed;
  int _authorityEpoch = 0;

  @override
  String? get accessToken => _accessToken;

  @override
  int get authorityEpoch => _authorityEpoch;

  @override
  String get authorityFingerprint =>
      _snapshot.currentContext?.authorityFingerprint ??
      '$_authorityEpoch:unauthenticated';

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  @override
  Future<void> signIn({
    required String identifier,
    required String password,
  }) async {
    _clear();
    final attemptEpoch = _authorityEpoch;
    try {
      final response = await _api.post(
        '/authentication/identity-sign-in',
        body: {
          'identifier': identifier.trim(),
          'password': password,
          'surface': 'PORTAL',
        },
        authenticated: false,
      );
      if (!_isCurrentEpoch(attemptEpoch)) return;
      switch (response.body['outcome']) {
        case 'SESSION_ESTABLISHED':
          final session = _map(response.body['session']);
          if (session == null) _throwInvalidResponse();
          _establishSession(session, response.headers);
          await _persistBiometricCredentialIfMatching();
        case 'CONTEXT_SELECTION_REQUIRED':
          final ticket = response.headers['x-nexa-context-ticket'];
          if (ticket == null || ticket.isEmpty) _throwInvalidResponse();
          _contextTicket = ticket;
          final contextsResponse = await _api.get(
            '/me/access-contexts',
            authenticated: false,
            contextTicket: ticket,
          );
          if (!_isCurrentEpoch(attemptEpoch) || _contextTicket != ticket) {
            return;
          }
          final rawContexts = contextsResponse.body['accessContexts'];
          if (rawContexts is! List) _throwInvalidResponse();
          final eligibleContexts = <BuyerAccessContext>[];
          for (final value in rawContexts) {
            final context = _parseContextOption(value);
            if (context == null) _throwInvalidResponse();
            eligibleContexts.add(context);
          }
          if (eligibleContexts
                  .map((context) => context.membershipId)
                  .toSet()
                  .length !=
              eligibleContexts.length) {
            _throwInvalidResponse();
          }
          if (eligibleContexts.isEmpty) {
            _throwNoBuyerContext();
          }
          _publish(
            BuyerAccessSnapshot(
              status: BuyerAccessStatus.choosingContext,
              authorityEpoch: attemptEpoch,
              availableContexts: eligibleContexts,
            ),
          );
        case 'NO_WORK_CONTEXT':
          _throwNoBuyerContext();
        default:
          _throwInvalidResponse();
      }
    } catch (error, stackTrace) {
      if (_isCurrentEpoch(attemptEpoch)) {
        _clear();
        Error.throwWithStackTrace(error, stackTrace);
      }
    }
  }

  @override
  Future<void> selectContext(String membershipId) async {
    final ticket = _contextTicket;
    final selectionEpoch = _authorityEpoch;
    final matches = _snapshot.availableContexts.where(
      (context) => context.membershipId == membershipId,
    );
    final selectedContext = matches.isEmpty ? null : matches.first;
    if (ticket == null || selectedContext == null) _throwInvalidResponse();

    _contextTicket = null;
    try {
      final response = await _api.post(
        '/me/access-context-selections',
        body: {'membershipId': membershipId},
        authenticated: false,
        contextTicket: ticket,
      );
      if (!_isCurrentEpoch(selectionEpoch)) return;
      _establishSession(
        response.body,
        response.headers,
        selectedContext: selectedContext,
      );
      await _persistBiometricCredentialIfMatching();
    } catch (error, stackTrace) {
      if (_isCurrentEpoch(selectionEpoch)) {
        _clear();
        Error.throwWithStackTrace(error, stackTrace);
      }
    }
  }

  @override
  Future<void> signOut() async {
    final token = _accessToken;
    _clear();
    try {
      await _biometricAccess.clearCredential();
    } catch (_) {
      // The server revocation below remains authoritative if local cleanup fails.
    }
    if (token != null) {
      try {
        await _api.post(
          '/authentication/sign-out',
          authenticated: false,
          bearerToken: token,
        );
      } on NexaApiFailure {
        // Local sign-out is authoritative for this client even if revocation
        // cannot be confirmed by the transport.
      } catch (_) {
        // Keep protected content closed when the best-effort call fails.
      }
    }
  }

  @override
  Future<bool> lockForBiometricLogin() async {
    final status = await biometricLoginStatus();
    if (!status.isEnabled) return false;
    _clear();
    return true;
  }

  @override
  Future<BuyerBiometricLoginStatus> biometricLoginStatus() async {
    final available = await _biometricAccess.isAvailable();
    final credential = await _biometricAccess.readCredential();
    final currentContext = _snapshot.currentContext;
    final credentialMatchesCurrent =
        currentContext == null ||
        credential?.scopeKey == currentContext.scopeKey;
    return BuyerBiometricLoginStatus(
      isEnabled: credential != null && credentialMatchesCurrent,
      canEnable:
          available &&
          _snapshot.isSignedIn &&
          currentContext != null &&
          _refreshToken != null,
      canSignIn: available && credential != null,
    );
  }

  @override
  Future<bool> enableBiometricLogin() async {
    final context = _snapshot.currentContext;
    final refreshToken = _refreshToken;
    if (!_snapshot.isSignedIn || context == null || refreshToken == null) {
      return false;
    }
    if (!await _biometricAccess.isAvailable()) return false;
    if (!await _biometricAccess.authenticate(
      reason: 'Confirma tu identidad para activar el acceso a Nexa.',
    )) {
      return false;
    }
    await _biometricAccess.writeCredential(
      BuyerBiometricCredential(
        nativeRefreshToken: refreshToken,
        scopeKey: context.scopeKey,
      ),
    );
    return true;
  }

  @override
  Future<void> disableBiometricLogin() => _biometricAccess.clearCredential();

  @override
  Future<void> signInWithBiometrics() async {
    final credential = await _biometricAccess.readCredential();
    if (credential == null || !await _biometricAccess.isAvailable()) return;
    if (!await _biometricAccess.authenticate(
      reason: 'Confirma tu identidad para entrar a Nexa Buyer.',
    )) {
      return;
    }

    _clear();
    final attemptEpoch = _authorityEpoch;
    try {
      final response = await _api.post(
        '/authentication/refresh',
        authenticated: false,
        nativeRefreshToken: credential.nativeRefreshToken,
      );
      if (!_isCurrentEpoch(attemptEpoch)) return;
      _establishSession(
        response.body,
        response.headers,
        requiredScopeKey: credential.scopeKey,
      );
      await _persistBiometricCredentialIfMatching();
    } catch (error, stackTrace) {
      if (_isCurrentEpoch(attemptEpoch)) {
        _clear();
        if (error is NexaApiFailure &&
            (error.statusCode == 401 || error.statusCode == 403)) {
          await _biometricAccess.clearCredential();
        }
      }
      Error.throwWithStackTrace(error, stackTrace);
    }
  }

  @override
  void invalidateLocalSession() => _clear();

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async {
    if (!_isCurrentEpoch(expectedAuthorityEpoch) || _accessToken == null) {
      return null;
    }
    if (_accessToken != tokenUsed) return _accessToken;
    final inFlight = _refreshInFlight;
    if (inFlight != null &&
        _refreshAuthorityEpoch == expectedAuthorityEpoch &&
        _refreshTokenUsed == tokenUsed) {
      return inFlight;
    }

    final refresh = _rotateSession(tokenUsed, expectedAuthorityEpoch);
    _refreshInFlight = refresh;
    _refreshAuthorityEpoch = expectedAuthorityEpoch;
    _refreshTokenUsed = tokenUsed;
    try {
      return await refresh;
    } finally {
      if (identical(_refreshInFlight, refresh)) {
        _refreshInFlight = null;
        _refreshAuthorityEpoch = null;
        _refreshTokenUsed = null;
      }
    }
  }

  Future<String?> _rotateSession(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async {
    final oldContext = _snapshot.currentContext;
    final refreshToken = _refreshToken;
    if (oldContext == null || refreshToken == null) {
      _clearIfCurrent(expectedAuthorityEpoch);
      throw _sessionExpired();
    }

    try {
      final response = await _api.post(
        '/authentication/refresh',
        authenticated: false,
        nativeRefreshToken: refreshToken,
      );
      if (!_isCurrentEpoch(expectedAuthorityEpoch) ||
          _accessToken != tokenUsed) {
        return null;
      }
      _establishSession(
        response.body,
        response.headers,
        preserveLease: true,
        requiredScopeKey: oldContext.scopeKey,
      );
      await _persistBiometricCredentialIfMatching();
      return _accessToken;
    } catch (_, stackTrace) {
      if (!_isCurrentEpoch(expectedAuthorityEpoch)) return null;
      _clear();
      Error.throwWithStackTrace(_sessionExpired(), stackTrace);
    }
  }

  void _establishSession(
    Map<String, Object?> response,
    Map<String, String> headers, {
    bool preserveLease = false,
    String? requiredScopeKey,
    BuyerAccessContext? selectedContext,
  }) {
    final session = _map(response['session']);
    final accessToken = _string(response['accessToken']);
    final refreshToken = _string(headers['x-nexa-refresh-token']);
    if (session == null || accessToken == null || refreshToken == null) {
      _throwInvalidResponse();
    }
    var context = _parseSessionContext(session);
    if (context == null ||
        _string(session['surface']) != 'PORTAL' ||
        !_stringSet(session['roles']).contains('BUYER') ||
        (requiredScopeKey != null && context.scopeKey != requiredScopeKey) ||
        (selectedContext != null &&
            context.scopeKey != selectedContext.scopeKey)) {
      _throwNoBuyerContext();
    }
    if (selectedContext != null) {
      context = BuyerAccessContext(
        membershipId: context.membershipId,
        userId: context.userId,
        tenantId: context.tenantId,
        tenantName: selectedContext.tenantName,
        tenantSlug: context.tenantSlug,
        workspaceId: context.workspaceId,
        workspaceName: selectedContext.workspaceName,
        workspaceSlug: context.workspaceSlug,
        roles: context.roles,
        permissions: context.permissions,
        authorizationVersion: context.authorizationVersion,
        displayName: context.displayName,
        email: context.email,
      );
    }

    _accessToken = accessToken;
    _refreshToken = refreshToken;
    _contextTicket = null;
    if (!preserveLease) _authorityEpoch++;
    _publish(
      BuyerAccessSnapshot(
        status: BuyerAccessStatus.signedIn,
        authorityEpoch: _authorityEpoch,
        currentContext: context,
      ),
    );
  }

  Future<void> _persistBiometricCredentialIfMatching() async {
    try {
      final credential = await _biometricAccess.readCredential();
      final context = _snapshot.currentContext;
      final refreshToken = _refreshToken;
      if (credential == null ||
          context == null ||
          credential.scopeKey != context.scopeKey ||
          refreshToken == null) {
        return;
      }
      await _biometricAccess.writeCredential(
        BuyerBiometricCredential(
          nativeRefreshToken: refreshToken,
          scopeKey: context.scopeKey,
        ),
      );
    } catch (_) {
      // Biometric persistence must not invalidate a valid server session.
    }
  }

  BuyerAccessContext? _parseSessionContext(Map<String, Object?> session) {
    final userId = _string(session['userId']);
    final membershipId = _string(session['membershipId']);
    final tenantId = _string(session['tenantId']);
    final tenantSlug = _string(session['tenantSlug']);
    final workspaceId = _string(session['workspaceId']);
    final workspaceSlug = _string(session['workspaceSlug']);
    if (userId == null ||
        membershipId == null ||
        tenantId == null ||
        tenantSlug == null ||
        workspaceId == null ||
        workspaceSlug == null) {
      return null;
    }
    return BuyerAccessContext(
      userId: userId,
      membershipId: membershipId,
      tenantId: tenantId,
      tenantName: _string(session['tenantName']) ?? tenantSlug,
      tenantSlug: tenantSlug,
      workspaceId: workspaceId,
      workspaceName: _string(session['workspaceName']) ?? workspaceSlug,
      workspaceSlug: workspaceSlug,
      roles: _stringSet(session['roles']),
      permissions: _stringSet(session['permissions']),
      authorizationVersion: session['authorizationVersion'] is int
          ? session['authorizationVersion'] as int
          : null,
      displayName: _string(session['displayName']),
      email: _string(session['email']),
    );
  }

  BuyerAccessContext? _parseContextOption(Object? value) {
    final context = _map(value);
    if (context == null) return null;
    final membershipId = _string(context['membershipId']);
    final tenantId = _string(context['tenantId']);
    final tenantName = _string(context['tenantName']);
    final tenantSlug = _string(context['tenantSlug']);
    final workspaceId = _string(context['workspaceId']);
    final workspaceName = _string(context['workspaceName']);
    final workspaceSlug = _string(context['workspaceSlug']);
    if (membershipId == null ||
        tenantId == null ||
        tenantName == null ||
        tenantSlug == null ||
        workspaceId == null ||
        workspaceName == null ||
        workspaceSlug == null) {
      return null;
    }
    return BuyerAccessContext(
      membershipId: membershipId,
      tenantId: tenantId,
      tenantName: tenantName,
      tenantSlug: tenantSlug,
      workspaceId: workspaceId,
      workspaceName: workspaceName,
      workspaceSlug: workspaceSlug,
    );
  }

  void _publish(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
    if (!_changes.isClosed) _changes.add(snapshot);
  }

  void _clear() {
    _authorityEpoch++;
    _accessToken = null;
    _refreshToken = null;
    _contextTicket = null;
    _refreshInFlight = null;
    _refreshAuthorityEpoch = null;
    _refreshTokenUsed = null;
    _publish(
      BuyerAccessSnapshot(
        status: BuyerAccessStatus.signedOut,
        authorityEpoch: _authorityEpoch,
      ),
    );
  }

  void _clearIfCurrent(int expectedEpoch) {
    if (_isCurrentEpoch(expectedEpoch)) _clear();
  }

  bool _isCurrentEpoch(int expectedEpoch) => _authorityEpoch == expectedEpoch;

  Never _throwNoBuyerContext() => throw const NexaApiFailure(
    code: 'buyer_access_required',
    statusCode: 403,
    userMessage: 'No active Buyer relationship is available for this account.',
  );

  Never _throwInvalidResponse() => throw const NexaApiFailure(
    code: 'invalid_authentication_response',
    userMessage: 'Nexa returned an incomplete access response.',
  );

  NexaApiFailure _sessionExpired() => const NexaApiFailure(
    code: 'session_expired',
    statusCode: 401,
    userMessage: 'Your session could not be safely renewed. Sign in again.',
  );

  Map<String, Object?>? _map(Object? value) =>
      value is Map<String, dynamic> ? Map<String, Object?>.from(value) : null;

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  Set<String> _stringSet(Object? value) =>
      value is List ? value.whereType<String>().toSet() : const {};

  @override
  Future<void> dispose() => _changes.close();
}
