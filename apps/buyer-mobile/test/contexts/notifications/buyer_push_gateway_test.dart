import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/notifications/infrastructure/buyer_push_registration_gateway.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _membership = '8d078c24-ed12-4ce0-91e6-e3b33b1bb982';
const _subscription = '04899bb2-11ed-4ef5-9fd6-7a24cc53c942';
const _key = '588fd2dc-7196-4309-bfc7-5d45ec0b7062';
const _context = BuyerAccessContext(
  membershipId: _membership,
  tenantId: 't',
  tenantName: 't',
  tenantSlug: 't',
  workspaceId: 'w',
  workspaceName: 'w',
  workspaceSlug: 'w',
);

void main() {
  test(
    'matches native subscription response and preserves command identity',
    () async {
      final api = _api((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/notifications/push-subscriptions');
        expect(request.headers['x-nexa-client'], 'NATIVE');
        expect(request.headers['idempotency-key'], _key);
        expect(jsonDecode(request.body), {
          'installationId': 'buyer-installation',
          'platform': 'ANDROID',
          'providerToken': 'fcm-fid:installation',
        });
        return http.Response(jsonEncode(_response()), 201);
      });
      expect(await _register(api), _subscription);
      api.close();
    },
  );

  test('rejects a registration returned for another membership', () async {
    final api = _api(
      (_) async => http.Response(
        jsonEncode({..._response(), 'recipientMembershipId': 'foreign'}),
        201,
      ),
    );
    await expectLater(_register(api), throwsFormatException);
    api.close();
  });

  test('rejects disabled subscriptions and mismatched surface', () async {
    for (final override in [
      {'status': 'DISABLED'},
      {'surface': 'PLATFORM'},
    ]) {
      final api = _api(
        (_) async =>
            http.Response(jsonEncode({..._response(), ...override}), 201),
      );
      await expectLater(_register(api), throwsFormatException);
      api.close();
    }
  });
}

Map<String, Object?> _response() => {
  'id': _subscription,
  'recipientMembershipId': _membership,
  'installationId': 'buyer-installation',
  'platform': 'ANDROID',
  'surface': 'PORTAL',
  'status': 'ENABLED',
};
Future<String> _register(NexaApiClient api) =>
    NexaBuyerPushRegistrationGateway(api).register(
      context: _context,
      installationId: 'buyer-installation',
      platform: 'ANDROID',
      destination: 'fcm-fid:installation',
      commandKey: _key,
    );

NexaApiClient _api(Future<http.Response> Function(http.Request) handler) =>
    NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.example.test',
        allowLocalHttp: false,
      ),
      httpClient: MockClient(handler),
    )..sessionCredentials = _Credentials();

final class _Credentials implements SessionCredentialProvider {
  @override
  String get accessToken => 'test-access';
  @override
  int get authorityEpoch => 1;
  @override
  String get authorityFingerprint => 'test-scope';
  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => null;
}
