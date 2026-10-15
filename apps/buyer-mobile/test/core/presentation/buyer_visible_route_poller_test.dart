import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:nexa_buyer_mobile/core/presentation/buyer_visible_route_poller.dart';

void main() {
  testWidgets('polls only while its exact route is visible', (tester) async {
    final router = GoRouter(
      initialLocation: '/orders',
      routes: [
        GoRoute(
          path: '/orders',
          builder: (context, state) => const Scaffold(body: Text('orders')),
        ),
        GoRoute(
          path: '/orders/:id',
          builder: (context, state) => const Scaffold(body: Text('detail')),
        ),
        GoRoute(
          path: '/other',
          builder: (context, state) => const Scaffold(body: Text('other')),
        ),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    var refreshes = 0;
    final poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: '/orders',
      interval: const Duration(seconds: 1),
      onRefresh: () async {
        refreshes++;
      },
    );

    expect(poller.isPolling, isTrue);
    await tester.pump(const Duration(seconds: 2));
    expect(refreshes, greaterThanOrEqualTo(1));

    router.go('/other');
    await tester.pumpAndSettle();
    expect(poller.isPolling, isFalse);
    final hiddenRefreshes = refreshes;
    await tester.pump(const Duration(seconds: 3));
    expect(refreshes, hiddenRefreshes);

    router.go('/orders');
    await tester.pumpAndSettle();
    expect(poller.isPolling, isTrue);
    await tester.pump();
    expect(refreshes, greaterThan(hiddenRefreshes));

    poller.dispose();
    expect(poller.isPolling, isFalse);
    router.dispose();
  });
}
