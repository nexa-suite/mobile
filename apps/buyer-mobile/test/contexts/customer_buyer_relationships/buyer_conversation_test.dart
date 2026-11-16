import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/core/application/error/nexa_api_failure.dart';
import 'package:nexa_buyer_mobile/contexts/customer_buyer_relationships/application/buyer_conversation_repository.dart';
import 'package:nexa_buyer_mobile/contexts/customer_buyer_relationships/presentation/buyer_conversation_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';

void main() {
  test(
    'unknown send restores and retries identical command after reopening',
    () async {
      final access = _Access(_snapshot(1, 'a'));
      final store = _Store();
      final repository = _Repository()..failSend = true;
      var model = BuyerConversationViewModel(repository, store, access);
      await model.load();
      await model.send('  Hola  ');
      expect(model.pending?.body, 'Hola');
      final key = model.pending!.key;
      model.dispose();
      model = BuyerConversationViewModel(repository, store, access);
      await model.load();
      expect(model.pending?.key, key);
      repository.failSend = false;
      await model.send('different text');
      expect(repository.sent, [('Hola', key), ('Hola', key)]);
      expect(model.pending, isNull);
      expect(store.saved, isEmpty);
      model.dispose();
      await access.dispose();
    },
  );
  test(
    'confirmed send stays confirmed when subsequent refresh fails',
    () async {
      final access = _Access(_snapshot(1, 'a'));
      final repository = _Repository();
      final model = BuyerConversationViewModel(repository, _Store(), access);
      await model.load();
      repository.failList = true;
      expect(await model.send('Hola'), isTrue);
      expect(model.pending, isNull);
      expect(model.message, isNotNull);
      model.dispose();
      await access.dispose();
    },
  );
  test('permission denial clears previously loaded messages', () async {
    final access = _Access(_snapshot(1, 'a'));
    final repository = _Repository();
    final model = BuyerConversationViewModel(repository, _Store(), access);
    repository.waiting = Future.value(
      BuyerMessagePage(
        [
          BuyerMessage(
            id: '1',
            body: 'private',
            senderKind: 'BUYER',
            createdAt: DateTime.now(),
          ),
        ],
        '1',
        false,
      ),
    );
    await model.load();
    expect(model.items, hasLength(1));
    repository.waiting = Future.error(
      const NexaApiFailure(
        code: 'denied',
        userMessage: 'denied',
        statusCode: 403,
      ),
    );
    await model.load();
    expect(model.items, isEmpty);
    expect(model.initialized, isFalse);
    model.dispose();
    await access.dispose();
  });
  test('discards in-flight message response after tenant switch', () async {
    final access = _Access(_snapshot(1, 'a'));
    final repository = _Repository();
    final completer = Completer<BuyerMessagePage>();
    repository.waiting = completer.future;
    final model = BuyerConversationViewModel(repository, _Store(), access);
    final load = model.load();
    await Future<void>.delayed(Duration.zero);
    access.emit(_snapshot(2, 'b'));
    completer.complete(
      BuyerMessagePage(
        [
          BuyerMessage(
            id: '1',
            body: 'private',
            senderKind: 'BUYER',
            createdAt: DateTime.now(),
          ),
        ],
        '1',
        false,
      ),
    );
    await load;
    expect(model.items, isEmpty);
    expect(model.initialized, isFalse);
    model.dispose();
    await access.dispose();
  });
}

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
    permissions: const {'buyer.sales.read', 'buyer.sales.write'},
  ),
);

final class _Access implements BuyerAccessRepository {
  _Access(this._snapshot);
  BuyerAccessSnapshot _snapshot;
  final _controller = StreamController<BuyerAccessSnapshot>.broadcast(
    sync: true,
  );
  @override
  BuyerAccessSnapshot get snapshot => _snapshot;
  @override
  Stream<BuyerAccessSnapshot> get changes => _controller.stream;
  void emit(BuyerAccessSnapshot value) {
    _snapshot = value;
    _controller.add(value);
  }

  @override
  Future<void> dispose() => _controller.close();
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

final class _Store implements PendingBuyerMessageStore {
  final saved = <String, PendingBuyerMessage>{};
  @override
  Future<void> clear(String scope) async {
    saved.remove(scope);
  }

  @override
  Future<PendingBuyerMessage?> read(String scope) async => saved[scope];
  @override
  Future<void> write(String scope, PendingBuyerMessage message) async {
    saved[scope] = message;
  }
}

final class _Repository implements BuyerConversationRepository {
  bool failSend = false;
  bool failList = false;
  Future<BuyerMessagePage>? waiting;
  final sent = <(String, String)>[];
  @override
  Future<BuyerMessagePage> list(String? cursor) => failList
      ? Future.error(StateError('offline'))
      : waiting ?? Future.value(const BuyerMessagePage([], null, false));
  @override
  Future<BuyerMessage> send(String body, String key) async {
    sent.add((body, key));
    if (failSend) throw StateError('unknown');
    return BuyerMessage(
      id: '1',
      body: body,
      senderKind: 'BUYER',
      createdAt: DateTime.now(),
    );
  }
}
