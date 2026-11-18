import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/notifications/application/buyer_push_registration.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test(
    'foreground inbox refresh does not wait for provider registration',
    () async {
      final access = _Access(_active(1, 'a'));
      final device = _Device()..registrationGate = Completer<bool>();
      var refreshed = 0;
      final registration = _openingRegistration(device, access, () async {
        refreshed++;
      }, () {})..start();
      await _flush();

      device.messages.add(null);
      await _flush();
      expect(refreshed, 1);

      device.registrationGate!.complete(true);
      await registration.dispose();
      await access.dispose();
      await device.dispose();
    },
  );

  test(
    'queued foreground invalidation cannot cross an authority change',
    () async {
      final access = _Access(_active(1, 'a'));
      final device = _Device();
      final firstRefresh = Completer<void>();
      var refreshed = 0;
      final registration = _openingRegistration(device, access, () async {
        refreshed++;
        if (refreshed == 1) await firstRefresh.future;
      }, () {})..start();
      await _flush();

      device.messages.add(null);
      await _flush();
      device.messages.add(null);
      access.emit(_active(2, 'b'));
      firstRefresh.complete();
      await _flush();
      expect(refreshed, 1);

      device.messages.add(null);
      await _flush();
      expect(refreshed, 2);
      await registration.dispose();
      await access.dispose();
      await device.dispose();
    },
  );

  test('tap opens while provider registration is blocked', () async {
    final access = _Access(_active(1, 'a'));
    final device = _Device()..registrationGate = Completer<bool>();
    var opened = 0;
    final registration = _openingRegistration(device, access, () async {}, () {
      opened++;
    })..start();
    await _flush();
    device.opened.add(null);
    await _flush();
    expect(opened, 1);
    device.registrationGate!.complete(true);
    await registration.dispose();
    await access.dispose();
    await device.dispose();
  });

  test('initial false cannot erase a concurrent tap', () async {
    final access = _Access(_active(1, 'a'));
    final device = _Device()..initialGate = Completer<bool>();
    var opened = 0;
    final registration = _openingRegistration(device, access, () async {}, () {
      opened++;
    })..start();
    await _flush();
    device.opened.add(null);
    device.initialGate!.complete(false);
    await _flush();
    expect(opened, 1);
    await registration.dispose();
    await access.dispose();
    await device.dispose();
  });

  test('duplicate taps coalesce during inbox refresh', () async {
    final access = _Access(_active(1, 'a'));
    final device = _Device();
    final refresh = Completer<void>();
    var opened = 0;
    final registration = _openingRegistration(
      device,
      access,
      () => refresh.future,
      () {
        opened++;
      },
    )..start();
    await _flush();
    device.opened.add(null);
    device.opened.add(null);
    refresh.complete();
    await _flush();
    expect(opened, 1);
    await registration.dispose();
    await access.dispose();
    await device.dispose();
  });

  for (final next in [
    const BuyerAccessSnapshot(status: BuyerAccessStatus.signedOut),
    _active(2, 'b'),
    _active(2, 'a', membership: 'other'),
  ]) {
    test(
      'scope change during inbox refresh prevents old tap opening',
      () async {
        final access = _Access(_active(1, 'a'));
        final device = _Device();
        final refresh = Completer<void>();
        var opened = 0;
        final registration = _openingRegistration(
          device,
          access,
          () => refresh.future,
          () {
            opened++;
          },
        )..start();
        await _flush();
        device.opened.add(null);
        access.emit(next);
        refresh.complete();
        await _flush();
        expect(opened, 0);
        await registration.dispose();
        await access.dispose();
        await device.dispose();
      },
    );
  }

  test('dispose prevents late inbox opening', () async {
    final access = _Access(_active(1, 'a'));
    final device = _Device();
    final refresh = Completer<void>();
    var opened = 0;
    final registration = _openingRegistration(
      device,
      access,
      () => refresh.future,
      () {
        opened++;
      },
    )..start();
    await _flush();
    device.opened.add(null);
    await registration.dispose();
    refresh.complete();
    await _flush();
    expect(opened, 0);
    await access.dispose();
    await device.dispose();
  });

  test('timed registration retry keeps the same idempotency key', () async {
    final access = _Access(_active(1, 'a'));
    final device = _Device();
    final gateway = _Gateway()..fail = true;
    final registration = BuyerPushRegistration(
      device: device,
      gateway: gateway,
      access: access,
      platform: 'ANDROID',
      refreshInbox: () async {},
      openInbox: () {},
      retryDelay: const Duration(milliseconds: 100),
    )..start();
    await _flush();
    device.ids.add('installation-a');
    await _flush();
    expect(gateway.requests, hasLength(1));
    gateway.fail = false;
    await Future<void>.delayed(const Duration(milliseconds: 200));
    await _flush();
    expect(gateway.requests, hasLength(2));
    expect(gateway.requests.first, gateway.requests.last);
    await registration.dispose();
    await access.dispose();
    await device.dispose();
  });

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
    expect(gateway.installations, hasLength(2));
    expect(gateway.installations.first, gateway.installations.last);
    expect(gateway.installations.first, matches(r'^buyer-[0-9a-f]{64}$'));
    expect(gateway.installations.first, isNot(contains('installation-a')));
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

BuyerPushRegistration _openingRegistration(
  _Device device,
  _Access access,
  Future<void> Function() refresh,
  void Function() open,
) => BuyerPushRegistration(
  device: device,
  gateway: _Gateway(),
  access: access,
  platform: 'ANDROID',
  refreshInbox: refresh,
  openInbox: open,
);

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

BuyerAccessSnapshot _active(
  int epoch,
  String tenant, {
  String membership = 'm',
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    userId: 'u',
    membershipId: membership,
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
  Completer<bool>? registrationGate;
  Completer<bool>? initialGate;
  var resets = 0;
  var initial = false;
  @override
  Stream<String> get registrations => ids.stream;
  @override
  Stream<void> get foregroundMessages => messages.stream;
  @override
  Stream<void> get openedMessages => opened.stream;
  @override
  Future<bool> register() async =>
      registrationGate == null ? true : await registrationGate!.future;
  @override
  Future<void> reset() async {
    resets++;
  }

  @override
  Future<bool> wasOpenedFromNotification() async =>
      initialGate == null ? initial : await initialGate!.future;
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
  final installations = <String>[];
  @override
  Future<String> register({
    required BuyerAccessContext context,
    required String installationId,
    required String platform,
    required String destination,
    required String commandKey,
  }) async {
    requests.add((destination, commandKey));
    installations.add(installationId);
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
