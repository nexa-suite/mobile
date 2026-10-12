import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

void main() {
  group('NexaApiOrigin', () {
    test('accepts only an exact HTTPS origin outside local debug', () {
      expect(
        NexaApiOrigin.parse(
          'https://api.nexa.example:8443/',
          allowLocalHttp: false,
        ).uri.toString(),
        'https://api.nexa.example:8443',
      );
      for (final value in [
        'http://api.nexa.example',
        'https://api.nexa.example/api/v1',
        'https://user:password@api.nexa.example',
        'https://api.nexa.example?redirect=https://other.example',
      ]) {
        expect(
          () => NexaApiOrigin.parse(value, allowLocalHttp: true),
          throwsFormatException,
          reason: value,
        );
      }
    });

    test('allows an explicit local HTTP origin only when enabled', () {
      expect(
        NexaApiOrigin.parse(
          'http://10.0.2.2:8080',
          allowLocalHttp: true,
        ).uri.host,
        '10.0.2.2',
      );
      expect(
        () =>
            NexaApiOrigin.parse('http://10.0.2.2:8080', allowLocalHttp: false),
        throwsFormatException,
      );
      expect(
        () => NexaApiOrigin.parse(
          'http://192.168.1.50:8080',
          allowLocalHttp: true,
        ),
        throwsFormatException,
      );
    });
  });

  test(
    'sends native Portal authority only to the configured API origin',
    () async {
      http.Request? captured;
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          captured = request;
          return http.Response('{"items":[]}', 200);
        }),
      );

      await client.get(
        '/catalog-items',
        query: {'page': '0'},
        bearerToken: 'access-secret',
      );

      expect(
        captured!.url.toString(),
        'https://api.nexa.example/api/v1/catalog-items?page=0',
      );
      expect(_headers(captured!)['x-nexa-surface'], 'PORTAL');
      expect(_headers(captured!)['x-nexa-client'], 'NATIVE');
      expect(_headers(captured!)['authorization'], 'Bearer access-secret');
      expect(_headers(captured!).containsKey('origin'), isFalse);
      expect(
        () => client.get('https://evil.example/path', authenticated: false),
        throwsA(isA<NexaApiFailure>()),
      );
      client.close();
    },
  );

  test(
    'refreshes an expired read once and retries with the rotated token',
    () async {
      final credentials = _FakeCredentials('old-access');
      final authorizationHeaders = <String?>[];
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          authorizationHeaders.add(_headers(request)['authorization']);
          if (authorizationHeaders.length == 1) {
            return http.Response('{"code":"EXPIRED"}', 401);
          }
          return http.Response('{"items":[]}', 200);
        }),
      )..sessionCredentials = credentials;

      await client.get('/sales-orders', refreshAfterUnauthorized: true);

      expect(authorizationHeaders, ['Bearer old-access', 'Bearer new-access']);
      expect(credentials.refreshCount, 1);
      client.close();
    },
  );

  test(
    'does not replay an old request under a replacement authority',
    () async {
      final credentials = _FakeCredentials('old-access');
      var requestCount = 0;
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((_) async {
          requestCount++;
          credentials.authorityEpoch++;
          credentials.replaceAccessToken('new-context-access');
          return http.Response('{"code":"EXPIRED"}', 401);
        }),
      )..sessionCredentials = credentials;

      await expectLater(
        client.get('/sales-orders', refreshAfterUnauthorized: true),
        throwsA(
          isA<NexaApiFailure>().having(
            (failure) => failure.code,
            'code',
            'session_expired',
          ),
        ),
      );

      expect(requestCount, 1);
      expect(credentials.accessToken, 'new-context-access');
      expect(credentials.refreshCount, 0);
      client.close();
    },
  );

  test(
    'rejects requests that combine two native authority credentials',
    () async {
      var requestCount = 0;
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((_) async {
          requestCount++;
          return http.Response('{}', 200);
        }),
      );

      await expectLater(
        client.post(
          '/me/access-context-selections',
          body: const {'membershipId': 'membership-1'},
          authenticated: false,
          contextTicket: 'ticket-secret',
          nativeRefreshToken: 'refresh-secret',
        ),
        throwsA(
          isA<NexaApiFailure>().having(
            (failure) => failure.code,
            'code',
            'invalid_authority_combination',
          ),
        ),
      );

      expect(requestCount, 0);
      client.close();
    },
  );

  test(
    'maps Problem Details without exposing server detail as user copy',
    () async {
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient(
          (request) async => http.Response(
            jsonEncode({
              'code': 'SCOPE_DENIED',
              'detail': 'Internal tenant key is private',
              'correlationId': 'correlation-123',
            }),
            403,
            headers: {'content-type': 'application/problem+json'},
          ),
        ),
      );

      await expectLater(
        client.get('/sales-orders', bearerToken: 'access-secret'),
        throwsA(
          isA<NexaApiFailure>()
              .having((failure) => failure.statusCode, 'status', 403)
              .having((failure) => failure.code, 'code', 'SCOPE_DENIED')
              .having(
                (failure) => failure.correlationId,
                'correlation',
                'correlation-123',
              )
              .having(
                (failure) => failure.userMessage,
                'user message',
                'This information is not available for your current access.',
              ),
        ),
      );
      client.close();
    },
  );

  test(
    'aborts a binary stream that exceeds its cap when length lies',
    () async {
      final streamingClient = _LyingLengthClient();
      final client = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: streamingClient,
      )..sessionCredentials = _FakeCredentials('access-secret');

      await expectLater(
        client.getBinary(
          '/business-documents/document-id/downloads',
          maxBytes: 4,
        ),
        throwsA(
          isA<NexaApiFailure>().having(
            (failure) => failure.code,
            'code',
            'response_too_large',
          ),
        ),
      );

      expect(streamingClient.request!.headers['accept'], '*/*');
      client.close();
    },
  );
}

Map<String, String> _headers(http.BaseRequest request) => {
  for (final entry in request.headers.entries)
    entry.key.toLowerCase(): entry.value,
};

final class _FakeCredentials implements SessionCredentialProvider {
  _FakeCredentials(this._accessToken);

  String? _accessToken;
  int refreshCount = 0;

  @override
  int authorityEpoch = 0;

  @override
  String? get accessToken => _accessToken;

  @override
  String get authorityFingerprint => '$authorityEpoch';

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async {
    if (authorityEpoch != expectedAuthorityEpoch || _accessToken != tokenUsed) {
      return null;
    }
    refreshCount++;
    _accessToken = 'new-access';
    return _accessToken;
  }

  void replaceAccessToken(String token) => _accessToken = token;
}

final class _LyingLengthClient extends http.BaseClient {
  http.Request? request;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    this.request = request as http.Request;
    return http.StreamedResponse(
      Stream<List<int>>.fromIterable([
        [1, 2, 3],
        [4, 5],
      ]),
      200,
      contentLength: 1,
      headers: const {'content-type': 'application/octet-stream'},
    );
  }
}
