import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'buyer_workspace_navigation.dart';
import 'buyer_home_widgets.dart';
import 'buyer_catalog_preview.dart';
import 'buyer_catalog_highlights.dart';
import 'buyer_shopping_guide.dart';
import '../contexts/catalog_commercial_policy/application/catalog_repository.dart';
import '../contexts/catalog_commercial_policy/presentation/catalog_view_model.dart';
import '../contexts/credit_receivables/application/credit_exposure_repository.dart';
import '../contexts/credit_receivables/presentation/buyer_credit_exposure_view_model.dart';
import '../contexts/sales_commitment/application/buyer_orders_repository.dart';
import '../contexts/sales_commitment/application/buyer_purchase_workspace_repository.dart';
import '../contexts/sales_commitment/application/purchase_request_repository.dart';
import '../contexts/sales_commitment/presentation/buyer_orders_view_model.dart';
import '../contexts/sales_commitment/presentation/buyer_requests_view_model.dart';
import '../contexts/tenant_access_governance/application/buyer_access_repository.dart';
import '../contexts/tenant_access_governance/presentation/buyer_access_view_model.dart';
import '../core/presentation/buyer_visible_route_poller.dart';

/// Dashboard projections assembled from existing Buyer repositories.
final class BuyerShoppingSummary extends StatefulWidget {
  const BuyerShoppingSummary({super.key});

  @override
  State<BuyerShoppingSummary> createState() => _BuyerShoppingSummaryState();
}

final class _BuyerShoppingSummaryState extends State<BuyerShoppingSummary> {
  late final CatalogViewModel _catalog;
  late final BuyerCreditExposureViewModel _credit;
  late final BuyerOrdersViewModel _orders;
  late final BuyerRequestsViewModel _requests;
  late final Listenable _changes;
  late final StreamSubscription<BuyerAccessSnapshot> _accessSubscription;
  bool _requestsQueried = false;
  GoRouter? _router;
  BuyerVisibleRoutePoller? _poller;

  @override
  void initState() {
    super.initState();
    final access = context.read<BuyerAccessRepository>();
    _catalog = CatalogViewModel(context.read<CatalogRepository>(), access);
    _credit = BuyerCreditExposureViewModel(
      context.read<BuyerCreditExposureRepository>(),
      access,
    );
    _orders = BuyerOrdersViewModel(
      context.read<BuyerOrdersRepository>(),
      access,
    );
    _requests = BuyerRequestsViewModel(
      context.read<BuyerPurchaseWorkspaceRepository>(),
      context.read<PurchaseRequestRepository>(),
      access,
    );
    _changes = Listenable.merge([_credit, _orders, _requests, _catalog]);
    _accessSubscription = access.changes.listen((_) {
      if (!mounted) return;
      setState(() => _requestsQueried = false);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_refresh());
      });
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_refresh());
    });
  }

  Future<void> _refresh() async {
    final access = context.read<BuyerAccessRepository>();
    final snapshot = access.snapshot;
    final authority = _accessLease(snapshot);
    final permissions =
        snapshot.currentContext?.permissions ?? const <String>{};
    final canReadRequests = permissions.contains('buyer.sales.read');
    await Future.wait([
      if (permissions.contains(CatalogViewModel.readPermission))
        _catalog.refresh(),
      if (permissions.contains(CatalogViewModel.readPermission) &&
          _catalog.filterOptionsState(CatalogFilterKind.category).status ==
              CatalogFilterOptionsStatus.idle)
        _catalog.loadFilterOptions(
          kind: CatalogFilterKind.category,
          search: '',
          page: 0,
        ),
      if (permissions.contains(BuyerCreditExposureViewModel.readPermission))
        _credit.refresh(),
      if (permissions.contains(BuyerOrdersViewModel.readPermission))
        _orders.refresh(),
      if (canReadRequests) _requests.refresh(),
    ]);
    if (mounted &&
        canReadRequests &&
        authority == _accessLease(access.snapshot)) {
      setState(() => _requestsQueried = true);
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final router = GoRouter.of(context);
    if (identical(_router, router)) return;
    _router = router;
    _poller?.dispose();
    _poller = BuyerVisibleRoutePoller(
      router: router,
      routePath: '/home',
      onRefresh: _refresh,
    );
  }

  @override
  void dispose() {
    _poller?.dispose();
    _accessSubscription.cancel();
    _catalog.dispose();
    _credit.dispose();
    _orders.dispose();
    _requests.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>().snapshot;
    if (!access.hasActiveBuyerScope) return const SizedBox.shrink();
    final current = access.currentContext!;
    final permissions = current.permissions;
    final canReadOrders = permissions.contains(
      BuyerOrdersViewModel.readPermission,
    );
    final canReadCredit = permissions.contains(
      BuyerCreditExposureViewModel.readPermission,
    );
    final canReadRequests = permissions.contains('buyer.sales.read');
    final canReadCatalog = permissions.contains(
      CatalogViewModel.readPermission,
    );
    return AnimatedBuilder(
      animation: _changes,
      builder: (context, _) {
        final ordersRefreshing =
            _orders.status == BuyerOrdersLoadStatus.loading;
        final ordersVisible =
            _orders.status == BuyerOrdersLoadStatus.current || ordersRefreshing;
        final order = ordersVisible && _orders.items.isNotEmpty
            ? _orders.items.first
            : null;
        final creditRefreshing =
            _credit.status == BuyerCreditExposureStatus.loading;
        final exposure =
            _credit.status == BuyerCreditExposureStatus.current ||
                creditRefreshing
            ? _credit.exposure
            : null;
        final request = _requestsQueried && _requests.requests.isNotEmpty
            ? _requests.requests.first
            : null;
        final catalogCurrent = _catalog.status == CatalogLoadStatus.current;
        final categories = _catalog.filterOptionsState(
          CatalogFilterKind.category,
        );
        final shopping = <Widget>[
          if (canReadCatalog)
            BuyerCatalogPreview(
              items: _catalog.items,
              total: catalogCurrent ? _catalog.totalItems : null,
              loading: _catalog.status == CatalogLoadStatus.loading,
              onOpen: () => context.go('/catalog'),
              onSelected: (item) => context.push(
                '/catalog/${Uri.encodeComponent(item.catalogItemId)}',
              ),
            ),
          if (canReadCatalog &&
              categories.status == CatalogFilterOptionsStatus.current &&
              categories.items.isNotEmpty)
            BuyerCategoryShortcuts(
              items: categories.items,
              onSelected: (option) {
                unawaited(
                  context.read<CatalogViewModel>().filter(
                    query: '',
                    brand: '',
                    category: option.name,
                    coldChain: '',
                    availableOnly: false,
                  ),
                );
                context.go('/catalog');
              },
            ),
          const BuyerShoppingGuide(),
        ];
        final activity = <Widget>[
          if (canReadOrders)
            BuyerOrdersWidget(
              total:
                  _orders.status == BuyerOrdersLoadStatus.current ||
                      order != null
                  ? _orders.total
                  : null,
              refreshing: ordersRefreshing,
              code: order?.number,
              status: order?.status,
              createdAt: order?.createdAt,
              amount: order?.total,
              currency: order?.currency,
              message: _orders.status == BuyerOrdersLoadStatus.current
                  ? 'No tienes pedidos activos.'
                  : _orders.message ?? 'Consultando pedidos…',
              onOpen: () => context.go('/orders'),
              onLatest: order == null
                  ? null
                  : () => context.push(
                      '/orders/${Uri.encodeComponent(order.id)}',
                    ),
            ),
          BuyerSecondaryWidgets(
            children: [
              if (canReadCredit)
                BuyerCreditWidget(
                  exposure: exposure,
                  refreshing: creditRefreshing,
                  message: _credit.message ?? 'Consultando tu crédito…',
                  onOpen: () => context.go('/credit'),
                ),
              if (canReadRequests)
                BuyerRequestWidget(
                  code: request?.code,
                  status: request?.status,
                  productCount: request?.lineCount,
                  refreshing: _requests.busy,
                  message: _requests.busy || !_requestsQueried
                      ? 'Consultando solicitudes…'
                      : _requests.message ?? 'Sin solicitudes recientes.',
                  onOpen: request == null
                      ? () => context.go('/requests')
                      : () => context.push(
                          '/requests/${Uri.encodeComponent(request.id)}',
                        ),
                ),
            ],
          ),
        ];
        return LayoutBuilder(
          builder: (context, constraints) {
            final wide =
                constraints.maxWidth >= 900 &&
                MediaQuery.textScalerOf(context).scale(16) / 16 < 1.3;
            Widget stack(List<Widget> children) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < children.length; i++) ...[
                  if (i > 0) const SizedBox(height: 12),
                  children[i],
                ],
              ],
            );
            return RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                children: [
                  Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 1120),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          BuyerHomeGreeting(
                            name: current.displayName,
                            onFavorites:
                                BuyerWorkspaceNavigation.canOpenLocation(
                                  access,
                                  '/favorites',
                                )
                                ? () => context.push('/favorites')
                                : null,
                          ),
                          const SizedBox(height: 12),
                          if (wide)
                            Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Expanded(flex: 6, child: stack(shopping)),
                                const SizedBox(width: 16),
                                Expanded(flex: 5, child: stack(activity)),
                              ],
                            )
                          else
                            stack([
                              ...shopping.take(shopping.length - 1),
                              ...activity,
                              shopping.last,
                            ]),
                          if (canReadCatalog && _catalog.items.length > 3) ...[
                            const SizedBox(height: 16),
                            BuyerCatalogHighlights(
                              items: _catalog.items.skip(3).take(3).toList(),
                              onSelected: (item) => context.push(
                                '/catalog/${Uri.encodeComponent(item.catalogItemId)}',
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }
}

String? _accessLease(BuyerAccessSnapshot snapshot) =>
    snapshot.hasActiveBuyerScope
    ? '${snapshot.authorityEpoch}:${snapshot.currentContext!.authorityFingerprint}'
    : null;
