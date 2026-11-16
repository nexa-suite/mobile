import '../../../core/network/nexa_api_client.dart';
import '../../tenant_access_governance/application/buyer_access_repository.dart';
import '../application/buyer_push_registration.dart';

final class NexaBuyerPushRegistrationGateway
    implements BuyerPushRegistrationGateway {
  NexaBuyerPushRegistrationGateway(this._api);

  final NexaApiClient _api;

  @override
  Future<String> register({
    required BuyerAccessContext context,
    required String installationId,
    required String platform,
    required String destination,
    required String commandKey,
  }) async {
    final response = await _api.post(
      '/notifications/push-subscriptions',
      body: {
        'installationId': installationId,
        'platform': platform,
        'providerToken': destination,
      },
      idempotencyKey: commandKey,
    );
    final body = response.body;
    final id = body['id'];
    if (id is! String ||
        !RegExp(
          r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
        ).hasMatch(id) ||
        body['recipientMembershipId'] != context.membershipId ||
        body['installationId'] != installationId ||
        body['platform'] != platform ||
        body['surface'] != 'PORTAL' ||
        body['status'] != 'ENABLED') {
      throw const FormatException('Invalid native push registration.');
    }
    return id;
  }
}
