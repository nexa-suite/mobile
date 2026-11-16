import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/contexts/payments/application/buyer_payment_methods.dart';
import 'package:nexa_buyer_mobile/contexts/payments/presentation/buyer_payment_methods_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test('unknown setup reuses persisted key after reopening', () async {
    final access = _Access(_snapshot(1, 'a'));
    final store = _Store();
    final repository = _Repository()..failSetup = true;
    var model = BuyerPaymentMethodsViewModel(
      repository,
      store,
      _SetupPort(),
      access,
    );
    await model.load();
    await model.setup();
    expect(store.keys.values, hasLength(1));
    final key = store.keys.values.single;
    model.dispose();
    model = BuyerPaymentMethodsViewModel(
      repository,
      store,
      _SetupPort(),
      access,
    );
    repository.failSetup = false;
    await model.load();
    await model.setup();
    expect(repository.setupKeys, [key, key]);
    expect(store.keys.values, [key]);
    expect(store.keys.values, isNot(contains('secret')));
    expect(model.message, contains('aparecerá'));
    repository.setupStatus = 'succeeded';
    await model.setup();
    expect(repository.setupKeys, [key, key, key]);
    expect(store.keys, isEmpty);
    expect(model.message, contains('confirmó'));
    model.dispose();
    await access.dispose();
  });
  test('scope change discards in-flight card list', () async {
    final access = _Access(_snapshot(1, 'a'));
    final repo = _Repository();
    final waiting = Completer<List<BuyerPaymentMethod>>();
    repo.waiting = waiting.future;
    final model = BuyerPaymentMethodsViewModel(
      repo,
      _Store(),
      _SetupPort(),
      access,
    );
    final request = model.load();
    access.emit(_snapshot(2, 'b'));
    waiting.complete([_card]);
    await request;
    expect(model.methods, isEmpty);
    expect(model.initialized, isFalse);
    model.dispose();
    await access.dispose();
  });
  test('default and delete reload server state; unknown mutation does not claim success', () async {
    final access = _Access(_snapshot(1, 'a'));
    final repo = _Repository();
    final model = BuyerPaymentMethodsViewModel(
      repo,
      _Store(),
      _SetupPort(),
      access,
    );
    await model.load();
    await model.mutate(_card.id, remove: false);
    expect(repo.defaults, [_card.id]);
    expect(repo.loads, 2);
    repo.failMutation = true;
    await model.mutate(_card.id, remove: true);
    expect(repo.removals, [_card.id]);
    expect(repo.loads, 3);
    expect(model.message, contains('No se pudo confirmar'));
    model.dispose();
    await access.dispose();
  });
}

const _card = BuyerPaymentMethod(
  id: '00000000-0000-4000-8000-000000000001',
  brand: 'visa',
  last4: '4242',
  expiryMonth: 12,
  expiryYear: 2030,
  isDefault: false,
);
BuyerAccessSnapshot _snapshot(int epoch, String tenant) => BuyerAccessSnapshot(
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
    permissions: const {'payment.read', 'payment.create'},
  ),
);

final class _Access implements BuyerAccessRepository {
  _Access(this._snapshot);
  BuyerAccessSnapshot _snapshot;
  final _stream = StreamController<BuyerAccessSnapshot>.broadcast(sync: true);
  @override
  BuyerAccessSnapshot get snapshot => _snapshot;
  @override
  Stream<BuyerAccessSnapshot> get changes => _stream.stream;
  void emit(BuyerAccessSnapshot value) {
    _snapshot = value;
    _stream.add(value);
  }

  @override
  Future<void> dispose() => _stream.close();
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

final class _Store implements BuyerSetupKeyStore {
  final keys = <String, String>{};
  @override
  Future<void> clear(String scope) async {
    keys.remove(scope);
  }

  @override
  Future<String?> read(String scope) async => keys[scope];
  @override
  Future<void> write(String scope, String key) async {
    keys[scope] = key;
  }
}

final class _SetupPort implements BuyerPaymentSetupPort {
  @override
  Future<BuyerSetupOutcome> present(
    BuyerPaymentSetup setup,
    bool Function() isCurrent,
  ) async => BuyerSetupOutcome.completed;
}

final class _Repository implements BuyerPaymentMethodsRepository {
  bool failSetup = false, failMutation = false;
  String setupStatus = 'requires_payment_method';
  int loads = 0;
  Future<List<BuyerPaymentMethod>>? waiting;
  final setupKeys = <String>[], defaults = <String>[], removals = <String>[];
  @override
  Future<List<BuyerPaymentMethod>> list() {
    loads++;
    return waiting ?? Future.value([_card]);
  }

  @override
  Future<BuyerPaymentSetup> setup(String key) async {
    setupKeys.add(key);
    if (failSetup) throw StateError('unknown');
    return BuyerPaymentSetup(
      id: 'seti_1',
      secret: 'secret',
      status: setupStatus,
      publishableKey: 'pk_test_example',
    );
  }

  @override
  Future<void> makeDefault(String id) async {
    defaults.add(id);
    if (failMutation) throw StateError('unknown');
  }

  @override
  Future<void> remove(String id) async {
    removals.add(id);
    if (failMutation) throw StateError('unknown');
  }
}
