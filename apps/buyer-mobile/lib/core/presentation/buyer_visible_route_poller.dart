import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

/// Polls a route's server projection only while that exact location is visible.
final class BuyerVisibleRoutePoller with WidgetsBindingObserver {
  BuyerVisibleRoutePoller({
    required this.router,
    required this.routePath,
    required this.onRefresh,
    this.changes,
    this.interval = const Duration(seconds: 15),
  }) {
    router.routerDelegate.addListener(_syncVisibility);
    WidgetsBinding.instance.addObserver(this);
    _syncVisibility();
  }

  final GoRouter router;
  final Future<void> Function() onRefresh;
  final String routePath;
  final Stream<void> Function()? changes;
  final Duration interval;
  Timer? _timer;
  StreamSubscription<void>? _changeSubscription;
  bool _initialized = false;
  bool _refreshing = false;
  bool _refreshPending = false;
  bool _disposed = false;

  bool get isPolling => _timer?.isActive ?? false;

  bool get _isVisible =>
      !_disposed &&
      WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed &&
      router.routerDelegate.currentConfiguration.isNotEmpty &&
      router.state.uri.path == routePath;

  void _syncVisibility() {
    if (_disposed) return;
    if (!_isVisible) {
      _timer?.cancel();
      _timer = null;
      _stopListeningForChanges();
      return;
    }
    if (_timer != null) return;
    final wasInitialized = _initialized;
    _initialized = true;
    _timer = Timer.periodic(interval, (_) => _refreshIfVisible());
    _listenForChanges();
    if (wasInitialized) _refreshIfVisible();
  }

  void _listenForChanges() {
    if (_changeSubscription != null || changes == null) return;
    _changeSubscription = changes!().listen(
      (_) => _refreshIfVisible(),
      onError: (Object _) {},
    );
  }

  void _stopListeningForChanges() {
    final subscription = _changeSubscription;
    _changeSubscription = null;
    if (subscription != null) unawaited(subscription.cancel());
  }

  void _refreshIfVisible() {
    if (!_isVisible) return;
    if (_refreshing) {
      _refreshPending = true;
      return;
    }
    _refreshing = true;
    unawaited(() async {
      try {
        await onRefresh();
      } catch (_) {
        // Route polling and change-feed reads share repository error handling.
      } finally {
        _refreshing = false;
        final refreshPending = _refreshPending;
        _refreshPending = false;
        if (refreshPending) _refreshIfVisible();
      }
    }());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) => _syncVisibility();

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _timer?.cancel();
    _timer = null;
    _stopListeningForChanges();
    router.routerDelegate.removeListener(_syncVisibility);
    WidgetsBinding.instance.removeObserver(this);
  }
}
