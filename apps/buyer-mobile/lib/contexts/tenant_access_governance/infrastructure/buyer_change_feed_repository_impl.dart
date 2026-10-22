import 'dart:async';

import '../application/buyer_access_repository.dart';
import '../application/buyer_change_feed_repository.dart';
import '../../../core/network/nexa_api_client.dart';

final class BuyerChangeFeedRepositoryImpl implements BuyerChangeFeedRepository {
  BuyerChangeFeedRepositoryImpl(this._api, this._access);

  final NexaApiClient _api;
  final BuyerAccessRepository _access;

  @override
  Stream<BuyerChangeFeedEvent> watch({required String readPermission}) {
    final initial = _access.snapshot;
    final context = initial.currentContext;
    if (!_canRead(initial, readPermission) || context == null) {
      return const Stream.empty();
    }

    final authorityEpoch = initial.authorityEpoch;
    final authorityFingerprint = context.authorityFingerprint;
    return Stream.multi((controller) {
      var stopped = false;
      StreamSubscription<BuyerAccessSnapshot>? accessSubscription;
      StreamSubscription<NexaApiChangeFeedEvent>? feedSubscription;

      bool isSameAuthority(BuyerAccessSnapshot snapshot) =>
          _canRead(snapshot, readPermission) &&
          snapshot.authorityEpoch == authorityEpoch &&
          snapshot.currentContext?.authorityFingerprint == authorityFingerprint;

      void stop() {
        if (stopped) return;
        stopped = true;
        unawaited(accessSubscription?.cancel());
        unawaited(feedSubscription?.cancel());
        unawaited(controller.close());
      }

      accessSubscription = _access.changes.listen(
        (snapshot) {
          if (!isSameAuthority(snapshot)) stop();
        },
        onError: (Object _) => stop(),
        onDone: stop,
      );
      feedSubscription = _api
          .watchChangeFeed(
            authorityEpoch: authorityEpoch,
            authorityFingerprint: authorityFingerprint,
          )
          .listen(
            (event) {
              if (isSameAuthority(_access.snapshot)) {
                controller.add(BuyerChangeFeedEvent(event.type));
              } else {
                stop();
              }
            },
            onError: (Object _) => stop(),
            onDone: stop,
          );

      if (!isSameAuthority(_access.snapshot)) stop();
      controller.onCancel = stop;
    }, isBroadcast: false);
  }

  bool _canRead(BuyerAccessSnapshot snapshot, String readPermission) {
    final context = snapshot.currentContext;
    return snapshot.hasActiveBuyerScope &&
        context != null &&
        context.permissions.contains(readPermission);
  }
}
