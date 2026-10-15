import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

/// Polls a route's server projection only while that exact location is visible.
final class BuyerVisibleRoutePoller with WidgetsBindingObserver {
  BuyerVisibleRoutePoller({
    required this.router,
    required this.routePath,
    required this.onRefresh,
    this.interval = const Duration(seconds: 15),
  }) {
    router.routeInformationProvider.addListener(_syncVisibility);
    WidgetsBinding.instance.addObserver(this);
    _syncVisibility();
  }

  final GoRouter router;
  final Future<void> Function() onRefresh;
  final String routePath;
  final Duration interval;
  Timer? _timer;
  bool _initialized = false;
  bool _refreshing = false;
  bool _disposed = false;

  bool get isPolling => _timer?.isActive ?? false;

  bool get _isVisible =>
      !_disposed &&
      WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed &&
      router.routeInformationProvider.value.uri.path == routePath;

  void _syncVisibility() {
    if (_disposed) return;
    if (!_isVisible) {
      _timer?.cancel();
      _timer = null;
      return;
    }
    if (_timer != null) return;
    final wasInitialized = _initialized;
    _initialized = true;
    _timer = Timer.periodic(interval, (_) => _refreshIfVisible());
    if (wasInitialized) _refreshIfVisible();
  }

  void _refreshIfVisible() {
    if (!_isVisible || _refreshing) return;
    _refreshing = true;
    unawaited(() async {
      try {
        await onRefresh();
      } finally {
        _refreshing = false;
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
    router.routeInformationProvider.removeListener(_syncVisibility);
    WidgetsBinding.instance.removeObserver(this);
  }
}
