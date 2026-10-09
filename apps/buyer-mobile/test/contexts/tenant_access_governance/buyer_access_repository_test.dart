import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/infrastructure/buyer_access_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  test('identity sign-in selects a server-provided Buyer membership', () async {
    final requests = <http.Request>[];
    final client = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((baseRequest) async {
        final request = baseRequest;
        requests.add(request);
        final path = request.url.path;
        if (path.endsWith('/authentication/identity-sign-in')) {
          return http.Response(
            jsonEncode({'outcome': 'CONTEXT_SELECTION_REQUIRED'}),
            200,
            headers: {
              'content-type': 'application/json',
              'x-nexa-context-ticket': 'ticket-secret',
            },
          );
        }
        if (path.endsWith('/me/access-contexts')) {
          return http.Response(
            jsonEncode({
              'accessContexts': [
                {
                  'membershipId': 'membership-buyer',
                  'tenantId': 'tenant-secret',
                  'tenantName': 'Farmacia del Valle',
                  'tenantSlug': 'farmacia-valle',
                  'workspaceId': 'workspace-secret',
                  'workspaceName': 'Principal',
                  'workspaceSlug': 'principal',
                },
              ],
            }),
            200,
          );
        }
        if (path.endsWith('/me/access-context-selections')) {
          expect(jsonDecode(request.body), {
            'membershipId': 'membership-buyer',
          });
          return http.Response(
            jsonEncode(_authenticationResponse(roles: ['BUYER'])),
            200,
            headers: {
              'content-type': 'application/json',
              'x-nexa-refresh-token': 'refresh-secret',
            },
          );
        }
        fail('Unexpected route ${request.url.path}');
      }),
    );
    final repository = BuyerAccessRepositoryImpl(client);
    final changes = <BuyerAccessSnapshot>[];
    final subscription = repository.changes.listen(changes.add);

    await repository.signIn(
      identifier: 'buyer@example.test',
      password: 'do-not-store',
    );

    expect(repository.snapshot.status, BuyerAccessStatus.choosingContext);
    expect(
      repository.snapshot.availableContexts.single.tenantName,
      'Farmacia del Valle',
    );
    expect(
      repository.snapshot.availableContexts.single.membershipId,
      'membership-buyer',
    );
    expect(changes.last.status, BuyerAccessStatus.choosingContext);
    final signInHeaders = _headers(requests[0]);
    expect(signInHeaders['x-nexa-surface'], 'PORTAL');
    expect(signInHeaders['x-nexa-client'], 'NATIVE');
    expect(signInHeaders.containsKey('authorization'), isFalse);
    expect(jsonDecode(requests[0].body), {
      'identifier': 'buyer@example.test',
      'password': 'do-not-store',
      'surface': 'PORTAL',
    });
    expect(_headers(requests[1])['x-nexa-context-ticket'], 'ticket-secret');
    expect(_headers(requests[1]).containsKey('authorization'), isFalse);

    await repository.selectContext('membership-buyer');
    expect(repository.snapshot.status, BuyerAccessStatus.signedIn);
    expect(repository.snapshot.currentContext?.workspaceName, 'Principal');
    expect(repository.accessToken, 'access-secret');
    expect(_headers(requests[2])['x-nexa-context-ticket'], 'ticket-secret');
    expect(_headers(requests[2]).containsKey('authorization'), isFalse);
    expect(_headers(requests[2])['x-nexa-client'], 'NATIVE');

    final establishedEpoch = repository.snapshot.authorityEpoch;
    repository.invalidateLocalSession();
    expect(repository.accessToken, isNull);
    expect(repository.snapshot.status, BuyerAccessStatus.signedOut);
    expect(repository.snapshot.currentContext, isNull);
    expect(repository.snapshot.authorityEpoch, greaterThan(establishedEpoch));
    expect(changes.last.status, BuyerAccessStatus.signedOut);

    await subscription.cancel();
    await repository.dispose();
    client.close();
  });

  test('rejects a non-Buyer session and drops its credentials', () async {
    final client = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(
        (_) async => http.Response(
          jsonEncode({
            'outcome': 'SESSION_ESTABLISHED',
            'session': _authenticationResponse(roles: ['SALES']),
          }),
          200,
          headers: {'x-nexa-refresh-token': 'refresh-secret'},
        ),
      ),
    );
    final repository = BuyerAccessRepositoryImpl(client);

    await expectLater(
      repository.signIn(identifier: 'sales@example.test', password: 'secret'),
      throwsA(isA<NexaApiFailure>()),
    );
    expect(repository.snapshot.status, BuyerAccessStatus.signedOut);
    expect(repository.accessToken, isNull);

    await repository.dispose();
    client.close();
  });

  test(
    'a late context selection cannot replace a newer signed-in identity',
    () async {
      final selectionStarted = Completer<void>();
      final selectionResponse = Completer<http.Response>();
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          if (request.url.path.endsWith('/authentication/identity-sign-in')) {
            final body = jsonDecode(request.body) as Map<String, dynamic>;
            if (body['identifier'] == 'old@example.test') {
              return http.Response(
                jsonEncode({'outcome': 'CONTEXT_SELECTION_REQUIRED'}),
                200,
                headers: {'x-nexa-context-ticket': 'old-ticket'},
              );
            }
            return http.Response(
              jsonEncode({
                'outcome': 'SESSION_ESTABLISHED',
                'session': _authenticationResponse(
                  roles: ['BUYER'],
                  accessToken: 'new-access',
                  tenantId: 'tenant-new',
                  membershipId: 'membership-new',
                ),
              }),
              200,
              headers: {'x-nexa-refresh-token': 'new-refresh'},
            );
          }
          if (request.url.path.endsWith('/me/access-contexts')) {
            return http.Response(
              jsonEncode({
                'accessContexts': [
                  {
                    'membershipId': 'membership-old',
                    'tenantId': 'tenant-old',
                    'tenantName': 'Old company',
                    'tenantSlug': 'old-company',
                    'workspaceId': 'workspace-old',
                    'workspaceName': 'Old workspace',
                    'workspaceSlug': 'old-workspace',
                  },
                ],
              }),
              200,
            );
          }
          if (request.url.path.endsWith('/me/access-context-selections')) {
            selectionStarted.complete();
            return selectionResponse.future;
          }
          fail('Unexpected route ${request.url.path}');
        }),
      );
      final repository = BuyerAccessRepositoryImpl(client);

      await repository.signIn(
        identifier: 'old@example.test',
        password: 'old-password',
      );
      final oldSelection = repository.selectContext('membership-old');
      await selectionStarted.future;
      await repository.signIn(
        identifier: 'new@example.test',
        password: 'new-password',
      );
      selectionResponse.complete(
        http.Response(
          jsonEncode(
            _authenticationResponse(
              roles: ['BUYER'],
              accessToken: 'old-access',
              tenantId: 'tenant-old',
              membershipId: 'membership-old',
            ),
          ),
          200,
          headers: {'x-nexa-refresh-token': 'old-refresh'},
        ),
      );
      await oldSelection;

      expect(repository.accessToken, 'new-access');
      expect(
        repository.snapshot.currentContext?.membershipId,
        'membership-new',
      );
      expect(repository.snapshot.currentContext?.tenantId, 'tenant-new');

      await repository.dispose();
      client.close();
    },
  );

  test(
    'an ambiguous refresh clears session without retrying the old token',
    () async {
      var protectedRequests = 0;
      var refreshRequests = 0;
      String? refreshCredentialSent;
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          if (request.url.path.endsWith('/authentication/identity-sign-in')) {
            return http.Response(
              jsonEncode({
                'outcome': 'SESSION_ESTABLISHED',
                'session': _authenticationResponse(roles: ['BUYER']),
              }),
              200,
              headers: {'x-nexa-refresh-token': 'refresh-secret'},
            );
          }
          if (request.url.path.endsWith('/catalog-items')) {
            protectedRequests++;
            return http.Response('{"code":"EXPIRED"}', 401);
          }
          if (request.url.path.endsWith('/authentication/refresh')) {
            refreshRequests++;
            refreshCredentialSent =
                request.headers['X-Nexa-Refresh-Token'] ??
                request.headers['x-nexa-refresh-token'];
            throw http.ClientException('connection lost after dispatch');
          }
          fail('Unexpected route ${request.url.path}');
        }),
      );
      final repository = BuyerAccessRepositoryImpl(client);
      client.sessionCredentials = repository;

      await repository.signIn(
        identifier: 'buyer@example.test',
        password: 'secret',
      );
      await expectLater(
        client.get('/catalog-items', refreshAfterUnauthorized: true),
        throwsA(
          isA<NexaApiFailure>().having(
            (failure) => failure.code,
            'code',
            'session_expired',
          ),
        ),
      );

      expect(refreshRequests, 1);
      expect(protectedRequests, 1);
      expect(refreshCredentialSent, 'refresh-secret');
      expect(repository.accessToken, isNull);
      expect(repository.snapshot.status, BuyerAccessStatus.signedOut);

      await repository.dispose();
      client.close();
    },
  );
}

Map<String, Object?> _authenticationResponse({
  required List<String> roles,
  String accessToken = 'access-secret',
  String tenantId = 'tenant-secret',
  String membershipId = 'membership-buyer',
}) => {
  'accessToken': accessToken,
  'tokenType': 'Bearer',
  'expiresIn': 900,
  'session': {
    'userId': 'user-private',
    'displayName': 'Buyer One',
    'email': 'buyer@example.test',
    'tenantId': tenantId,
    'tenantSlug': 'farmacia-valle',
    'workspaceId': 'workspace-secret',
    'workspaceSlug': 'principal',
    'membershipId': membershipId,
    'roles': roles,
    'permissions': ['CATALOG_READ', 'BUYER_ORDER_READ'],
    'authorizationVersion': 7,
    'surface': 'PORTAL',
  },
};

Map<String, String> _headers(http.BaseRequest request) => {
  for (final entry in request.headers.entries)
    entry.key.toLowerCase(): entry.value,
};
