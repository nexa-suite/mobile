import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/infrastructure/buyer_mobile_terms_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  const termsContent = 'Términos publicados por el servidor.';
  final digest = sha256.convert(utf8.encode(termsContent)).toString();

  test('public terms are fetched without an authorization header', () async {
    final client = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((request) async {
        expect(request.url.path, '/api/v1/legal/mobile-terms');
        expect(request.headers['authorization'], isNull);
        return http.Response(jsonEncode(_document(termsContent, digest)), 200);
      }),
    );
    final repository = BuyerMobileTermsRepositoryImpl(
      client,
      _Access.signedIn(),
    );

    final document = await repository.fetchPublicTerms();

    expect(document.version, '17');
    expect(document.content, termsContent);
    expect(document.contentDigest, digest);
    client.close();
  });

  test('acceptance posts the exact API document identity and digest', () async {
    final requests = <http.Request>[];
    final credentials = _Credentials();
    final client = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((request) async {
        requests.add(request);
        if (request.url.path.endsWith('/legal/mobile-terms')) {
          return http.Response(
            jsonEncode(_document(termsContent, digest)),
            200,
          );
        }
        return http.Response(
          jsonEncode({
            ..._document(termsContent, digest),
            'accepted': true,
            'acceptedAt': '2026-11-17T12:00:00Z',
          }),
          200,
        );
      }),
    )..sessionCredentials = credentials;
    final repository = BuyerMobileTermsRepositoryImpl(
      client,
      _Access.signedIn(),
    );
    final document = await repository.fetchPublicTerms();

    final acceptance = await repository.accept(document);

    final post = requests.singleWhere((request) => request.method == 'POST');
    expect(post.url.path, '/api/v1/me/legal-acceptance');
    expect(post.headers['authorization'], 'Bearer test-access-token');
    expect(jsonDecode(post.body), {
      'documentId': 'mobile-terms',
      'version': '17',
      'contentDigest': digest,
    });
    expect(acceptance.accepted, isTrue);
    expect(acceptance.version, '17');
    client.close();
  });

  test('rejects a document whose content does not match its digest', () async {
    final client = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(
        (_) async => http.Response(
          jsonEncode(_document('changed content', digest)),
          200,
        ),
      ),
    );
    final repository = BuyerMobileTermsRepositoryImpl(
      client,
      _Access.signedOut(),
    );

    await expectLater(
      repository.fetchPublicTerms(),
      throwsA(isA<NexaApiFailure>()),
    );
    client.close();
  });
}

Map<String, Object?> _document(String content, String digest) => {
  'documentId': 'mobile-terms',
  'version': '17',
  'content': content,
  'contentDigest': digest,
};

final class _Access implements BuyerAccessRepository {
  _Access(this._snapshot);

  factory _Access.signedIn() => _Access(_activeSnapshot);

  factory _Access.signedOut() =>
      _Access(const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut));

  static const _activeSnapshot = BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedIn,
    authorityEpoch: 4,
    currentContext: BuyerAccessContext(
      membershipId: 'membership-1',
      tenantId: 'tenant-1',
      tenantName: 'Tenant',
      tenantSlug: 'tenant',
      workspaceId: 'workspace-1',
      workspaceName: 'Workspace',
      workspaceSlug: 'workspace',
      userId: 'user-1',
      roles: {'BUYER'},
    ),
  );

  BuyerAccessSnapshot _snapshot;
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast();

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;
  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;
  @override
  void invalidateLocalSession() => _snapshot = const BuyerAccessSnapshot(
    status: BuyerAccessStatus.signedOut,
  );
  @override
  Future<void> signIn({
    required String identifier,
    required String password,
  }) async {}
  @override
  Future<void> selectContext(String membershipId) async {}
  @override
  Future<void> signOut() async {}
  @override
  Future<void> dispose() => _changes.close();
}

final class _Credentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'test-access-token';
  @override
  int get authorityEpoch => 4;
  @override
  String get authorityFingerprint =>
      _Access._activeSnapshot.currentContext!.authorityFingerprint;
  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => null;
}
