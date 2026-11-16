import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/notifications/application/buyer_push_registration.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test('uncertain registration retries the same destination and key', () async {
    final access = _Access(_active(1, 'a'));
    final device = _Device();
    final gateway = _Gateway()..fail = true;
    final registration = _registration(device, gateway, access)..start();
    await _flush();
    device.ids.add('installation-a');
    await _flush();
    expect(gateway.requests, hasLength(1));
    gateway.fail = false;
    registration.refresh();
    await _flush();
    expect(gateway.requests, hasLength(2));
    expect(gateway.requests.first, gateway.requests.last);
    expect(gateway.requests.first.$1, 'fcm-fid:installation-a');
    expect(device.resets, 1);
    await registration.dispose();
    await access.dispose();
    await device.dispose();
  });

  test(
    'tenant switch discards the old registration and resets installation',
    () async {
      final access = _Access(_active(1, 'a'));
      final device = _Device();
      final gateway = _Gateway();
      final registration = _registration(device, gateway, access)..start();
      await _flush();
      device.ids.add('installation-a');
      await _flush();
      access.emit(_active(2, 'b'));
      await _flush();
      expect(device.resets, 2);
      registration.refresh();
      await _flush();
      expect(gateway.requests, hasLength(1));
      device.ids.add('installation-b');
      await _flush();
      expect(gateway.requests.last.$1, 'fcm-fid:installation-b');
      expect(gateway.tenants, ['a', 'b']);
      access.emit(
        const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut),
      );
      await _flush();
      device.ids.add('late-registration');
      await _flush();
      expect(device.resets, 3);
      expect(gateway.requests, hasLength(2));
      await registration.dispose();
      await access.dispose();
      await device.dispose();
    },
  );

  test(
    'notification opens only authorized inbox and never payload URLs',
    () async {
      final access = _Access(
        const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut),
      );
      final device = _Device()..initial = true;
      var opened = 0;
      var refreshed = 0;
      final registration = BuyerPushRegistration(
        device: device,
        gateway: _Gateway(),
        access: access,
        platform: 'ANDROID',
        refreshInbox: () async {
          refreshed++;
        },
        openInbox: () {
          opened++;
        },
      )..start();
      await _flush();
      expect(opened, 0);
      access.emit(_active(1, 'a'));
      await _flush();
      expect(opened, 1);
      expect(refreshed, 1);
      access.emit(
        const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut),
      );
      device.messages.add(null);
      await _flush();
      expect(refreshed, 1);
      await registration.dispose();
      await access.dispose();
      await device.dispose();
    },
  );
}

Future<void> _flush() async {
  for (var i = 0; i < 12; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

BuyerPushRegistration _registration(
  _Device device,
  _Gateway gateway,
  _Access access,
) => BuyerPushRegistration(
  device: device,
  gateway: gateway,
  access: access,
  platform: 'ANDROID',
  refreshInbox: () async {},
  openInbox: () {},
);

BuyerAccessSnapshot _active(int epoch, String tenant) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    userId: 'u',
    membershipId: 'm',
    tenantId: tenant,
    tenantName: tenant,
    tenantSlug: tenant,
    workspaceId: 'w',
    workspaceName: 'w',
    workspaceSlug: 'w',
    roles: const {'BUYER'},
    permissions: const {'notification.read'},
  ),
);

final class _Device implements BuyerPushDevice {
  final ids = StreamController<String>.broadcast(sync: true);
  final messages = StreamController<void>.broadcast(sync: true);
  final opened = StreamController<void>.broadcast(sync: true);
  var resets = 0;
  var initial = false;
  @override
  Stream<String> get registrations => ids.stream;
  @override
  Stream<void> get foregroundMessages => messages.stream;
  @override
  Stream<void> get openedMessages => opened.stream;
  @override
  Future<bool> register() async => true;
  @override
  Future<void> reset() async {
    resets++;
  }

  @override
  Future<bool> wasOpenedFromNotification() async => initial;
  Future<void> dispose() async {
    await ids.close();
    await messages.close();
    await opened.close();
  }
}

final class _Gateway implements BuyerPushRegistrationGateway {
  var fail = false;
  final requests = <(String, String)>[];
  final tenants = <String>[];
  @override
  Future<String> register({
    required BuyerAccessContext context,
    required String installationId,
    required String platform,
    required String destination,
    required String commandKey,
  }) async {
    requests.add((destination, commandKey));
    tenants.add(context.tenantId);
    if (fail) throw StateError('provider unavailable');
    return 'id';
  }
}

final class _Access implements BuyerAccessRepository {
  _Access(this._snapshot);
  BuyerAccessSnapshot _snapshot;
  final controller = StreamController<BuyerAccessSnapshot>.broadcast(
    sync: true,
  );
  @override
  BuyerAccessSnapshot get snapshot => _snapshot;
  @override
  Stream<BuyerAccessSnapshot> get changes => controller.stream;
  void emit(BuyerAccessSnapshot value) {
    _snapshot = value;
    controller.add(value);
  }

  @override
  Future<void> dispose() => controller.close();
  @override
  void invalidateLocalSession() {}
  @override
  Future<void> selectContext(String membershipId) async {}
  @override
  Future<void> signIn({
    required String identifier,
    required String password,
  }) async {}
  @override
  Future<void> signOut() async {}
}
