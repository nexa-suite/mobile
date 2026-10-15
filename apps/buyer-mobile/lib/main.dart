import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';

import 'app/buyer_workspace_navigation.dart';
import 'contexts/business_documents/application/business_documents_repository.dart';
import 'contexts/business_documents/infrastructure/business_documents_repository_impl.dart';
import 'contexts/business_documents/presentation/business_document_detail_page.dart';
import 'contexts/business_documents/presentation/business_documents_page.dart';
import 'contexts/business_documents/presentation/business_documents_view_model.dart';
import 'contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'contexts/catalog_commercial_policy/infrastructure/catalog_repository_impl.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_page.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_item_detail_page.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_item_detail_view_model.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_view_model.dart';
import 'contexts/credit_receivables/application/credit_exposure_repository.dart';
import 'contexts/credit_receivables/infrastructure/buyer_credit_exposure_repository_impl.dart';
import 'contexts/credit_receivables/presentation/buyer_credit_exposure_page.dart';
import 'contexts/credit_receivables/presentation/buyer_credit_exposure_view_model.dart';
import 'contexts/notifications/application/buyer_notifications_repository.dart';
import 'contexts/notifications/infrastructure/buyer_notifications_repository_impl.dart';
import 'contexts/notifications/presentation/buyer_notification_preferences_page.dart';
import 'contexts/notifications/presentation/buyer_notifications_page.dart';
import 'contexts/notifications/presentation/buyer_notifications_view_model.dart';
import 'contexts/payments/application/buyer_payments_repository.dart';
import 'contexts/payments/application/buyer_order_payment_capability_query.dart';
import 'contexts/payments/application/buyer_wallet_repository.dart';
import 'contexts/payments/application/buyer_wallet_checkout_port.dart';
import 'contexts/payments/application/buyer_wallet_recharge_command_store.dart';
import 'contexts/payments/application/payment_report_idempotency_store.dart';
import 'contexts/payments/infrastructure/buyer_payments_repository_impl.dart';
import 'contexts/payments/infrastructure/buyer_wallet_repository_impl.dart';
import 'contexts/payments/infrastructure/stripe_buyer_wallet_checkout.dart';
import 'contexts/payments/infrastructure/file_buyer_wallet_recharge_command_store.dart';
import 'contexts/payments/infrastructure/file_payment_report_idempotency_store.dart';
import 'contexts/payments/presentation/buyer_payment_activity_page.dart';
import 'contexts/payments/presentation/buyer_payment_activity_view_model.dart';
import 'contexts/payments/presentation/buyer_wallet_page.dart';
import 'contexts/payments/presentation/buyer_wallet_view_model.dart';
import 'contexts/fulfillment_delivery/infrastructure/buyer_deliveries_repository_impl.dart';
import 'contexts/fulfillment_delivery/application/buyer_deliveries_repository.dart';
import 'contexts/fulfillment_delivery/application/buyer_receipt_idempotency_store.dart';
import 'contexts/fulfillment_delivery/application/buyer_receipt_evidence_idempotency_store.dart';
import 'contexts/fulfillment_delivery/infrastructure/file_buyer_receipt_idempotency_store.dart';
import 'contexts/fulfillment_delivery/infrastructure/file_buyer_receipt_evidence_idempotency_store.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_deliveries_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_delivery_detail_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_deliveries_view_model.dart';
import 'contexts/sales_commitment/application/buyer_orders_repository.dart';
import 'contexts/sales_commitment/application/buyer_purchase_workspace_repository.dart';
import 'contexts/sales_commitment/application/purchase_request_repository.dart';
import 'contexts/sales_commitment/infrastructure/buyer_orders_repository_impl.dart';
import 'contexts/sales_commitment/infrastructure/file_purchase_request_idempotency_store.dart';
import 'contexts/sales_commitment/infrastructure/purchase_request_repository_impl.dart';
import 'contexts/sales_commitment/presentation/buyer_order_detail_page.dart';
import 'contexts/sales_commitment/presentation/buyer_orders_page.dart';
import 'contexts/sales_commitment/presentation/buyer_orders_view_model.dart';
import 'contexts/sales_commitment/presentation/purchase_request_composer_page.dart';
import 'contexts/sales_commitment/presentation/purchase_request_composer_view_model.dart';
import 'contexts/sales_commitment/presentation/buyer_requests_view_model.dart';
import 'contexts/sales_commitment/presentation/buyer_requests_page.dart';
import 'contexts/sales_commitment/presentation/buyer_request_detail_page.dart';
import 'contexts/sales_commitment/presentation/buyer_draft_view_model.dart';
import 'contexts/sales_commitment/presentation/buyer_draft_page.dart';
import 'contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'contexts/tenant_access_governance/infrastructure/buyer_access_repository_impl.dart';
import 'contexts/tenant_access_governance/presentation/buyer_access_view_model.dart';
import 'contexts/tenant_access_governance/presentation/context_selection_page.dart';
import 'contexts/tenant_access_governance/presentation/sign_in_page.dart';
import 'core/network/nexa_api_client.dart';
import 'core/presentation/nexa_buyer_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final walletCheckout = await StripeBuyerWalletCheckout.configure(
    const String.fromEnvironment('NEXA_STRIPE_PUBLISHABLE_KEY'),
  );
  const publicOsmTiles = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  const configuredMapTiles = String.fromEnvironment(
    'NEXA_MAP_TILE_URL_TEMPLATE',
  );
  const configuredMapAttribution = String.fromEnvironment(
    'NEXA_MAP_ATTRIBUTION',
  );
  final mapTileUrlTemplate = configuredMapTiles;
  final mapAttribution = configuredMapAttribution.isNotEmpty
      ? configuredMapAttribution
      : mapTileUrlTemplate == publicOsmTiles
      ? '© OpenStreetMap contributors'
      : '';
  runApp(
    BuyerMobileApp(
      apiBaseUrl: const String.fromEnvironment('NEXA_API_BASE_URL'),
      allowLocalHttp: kDebugMode,
      walletCheckout: walletCheckout,
      mapTileUrlTemplate: mapTileUrlTemplate,
      mapAttribution: mapAttribution,
    ),
  );
}

final class BuyerMobileApp extends StatefulWidget {
  const BuyerMobileApp({
    super.key,
    required this.apiBaseUrl,
    required this.allowLocalHttp,
    this.mapTileUrlTemplate = '',
    this.mapAttribution = '',
    this.httpClient,
    this.walletCheckout,
  });

  final String apiBaseUrl;
  final bool allowLocalHttp;
  final String mapTileUrlTemplate;
  final String mapAttribution;
  final http.Client? httpClient;
  final BuyerWalletCheckoutPort? walletCheckout;

  @override
  State<BuyerMobileApp> createState() => _BuyerMobileAppState();
}

final class _BuyerMobileAppState extends State<BuyerMobileApp> {
  http.Client? _httpClient;
  BuyerAccessRepositoryImpl? _accessRepository;
  BuyerAccessViewModel? _accessViewModel;
  CatalogRepositoryImpl? _catalogRepository;
  BuyerCreditExposureRepositoryImpl? _creditExposureRepository;
  BuyerPaymentsRepositoryImpl? _paymentsRepository;
  BuyerWalletRepositoryImpl? _walletRepository;
  FileBuyerWalletRechargeCommandStore? _walletRechargeCommandStore;
  FilePaymentReportIdempotencyStore? _paymentIdempotencyStore;
  BuyerDeliveriesRepositoryImpl? _deliveriesRepository;
  FileBuyerReceiptIdempotencyStore? _buyerReceiptIdempotencyStore;
  FileBuyerReceiptEvidenceIdempotencyStore?
  _buyerReceiptEvidenceIdempotencyStore;
  BuyerOrdersRepositoryImpl? _ordersRepository;
  PurchaseRequestRepositoryImpl? _purchaseRequestRepository;
  BusinessDocumentsRepositoryImpl? _businessDocumentsRepository;
  BuyerNotificationsRepositoryImpl? _notificationsRepository;
  BuyerNotificationsViewModel? _notificationsViewModel;
  GoRouter? _router;
  bool _configurationInvalid = false;

  @override
  void initState() {
    super.initState();
    try {
      final origin = NexaApiOrigin.parse(
        widget.apiBaseUrl,
        allowLocalHttp: widget.allowLocalHttp,
      );
      _httpClient = widget.httpClient ?? http.Client();
      final api = NexaApiClient(origin: origin, httpClient: _httpClient!);
      _accessRepository = BuyerAccessRepositoryImpl(api);
      api.sessionCredentials = _accessRepository;
      _accessViewModel = BuyerAccessViewModel(_accessRepository!);
      _catalogRepository = CatalogRepositoryImpl(api);
      _creditExposureRepository = BuyerCreditExposureRepositoryImpl(api);
      _paymentsRepository = BuyerPaymentsRepositoryImpl(api);
      _walletRepository = BuyerWalletRepositoryImpl(api);
      _walletRechargeCommandStore = FileBuyerWalletRechargeCommandStore();
      _paymentIdempotencyStore = FilePaymentReportIdempotencyStore();
      _deliveriesRepository = BuyerDeliveriesRepositoryImpl(api);
      _buyerReceiptIdempotencyStore = FileBuyerReceiptIdempotencyStore();
      _buyerReceiptEvidenceIdempotencyStore =
          FileBuyerReceiptEvidenceIdempotencyStore();
      _ordersRepository = BuyerOrdersRepositoryImpl(api);
      _purchaseRequestRepository = PurchaseRequestRepositoryImpl(
        api,
        FilePurchaseRequestIdempotencyStore(),
      );
      _businessDocumentsRepository = BusinessDocumentsRepositoryImpl(api);
      _notificationsRepository = BuyerNotificationsRepositoryImpl(api);
      _notificationsViewModel = BuyerNotificationsViewModel(
        _notificationsRepository!,
        _accessRepository!,
      );
      _router = _createRouter(
        _accessViewModel!,
        _paymentsRepository!,
        _paymentIdempotencyStore!,
        widget.mapTileUrlTemplate,
        widget.mapAttribution,
      );
    } on FormatException {
      _configurationInvalid = true;
    }
  }

  @override
  void dispose() {
    _router?.dispose();
    _notificationsViewModel?.dispose();
    _accessViewModel?.dispose();
    _accessRepository?.dispose();
    _httpClient?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final access = _accessRepository;
    final accessViewModel = _accessViewModel;
    final catalog = _catalogRepository;
    final creditExposure = _creditExposureRepository;
    final payments = _paymentsRepository;
    final wallet = _walletRepository;
    final walletRechargeCommandStore = _walletRechargeCommandStore;
    final paymentIdempotencyStore = _paymentIdempotencyStore;
    final deliveries = _deliveriesRepository;
    final buyerReceiptIdempotencyStore = _buyerReceiptIdempotencyStore;
    final buyerReceiptEvidenceIdempotencyStore =
        _buyerReceiptEvidenceIdempotencyStore;
    final orders = _ordersRepository;
    final purchaseRequests = _purchaseRequestRepository;
    final businessDocuments = _businessDocumentsRepository;
    final notifications = _notificationsRepository;
    final notificationsViewModel = _notificationsViewModel;
    final router = _router;
    if (_configurationInvalid ||
        access == null ||
        accessViewModel == null ||
        catalog == null ||
        creditExposure == null ||
        payments == null ||
        wallet == null ||
        walletRechargeCommandStore == null ||
        paymentIdempotencyStore == null ||
        deliveries == null ||
        buyerReceiptIdempotencyStore == null ||
        buyerReceiptEvidenceIdempotencyStore == null ||
        orders == null ||
        purchaseRequests == null ||
        businessDocuments == null ||
        notifications == null ||
        notificationsViewModel == null ||
        router == null) {
      return const _ConfigurationApp();
    }

    return MultiProvider(
      providers: [
        Provider<BuyerAccessRepository>.value(value: access),
        Provider<CatalogRepository>.value(value: catalog),
        Provider<BuyerCreditExposureRepository>.value(value: creditExposure),
        Provider<BuyerPaymentsRepository>.value(value: payments),
        Provider<BuyerWalletRepository>.value(value: wallet),
        Provider<BuyerWalletRechargeCommandStore>.value(
          value: walletRechargeCommandStore,
        ),
        Provider<BuyerOrderPaymentCapabilityQuery>.value(value: wallet),
        Provider<PaymentReportIdempotencyStore>.value(
          value: paymentIdempotencyStore,
        ),
        Provider<BuyerDeliveriesRepository>.value(value: deliveries),
        Provider<BuyerReceiptIdempotencyStore>.value(
          value: buyerReceiptIdempotencyStore,
        ),
        Provider<BuyerReceiptEvidenceIdempotencyStore>.value(
          value: buyerReceiptEvidenceIdempotencyStore,
        ),
        Provider<BuyerOrdersRepository>.value(value: orders),
        Provider<PurchaseRequestRepository>.value(value: purchaseRequests),
        Provider<BuyerPurchaseWorkspaceRepository>.value(
          value: purchaseRequests,
        ),
        Provider<BusinessDocumentsRepository>.value(value: businessDocuments),
        Provider<BuyerNotificationsRepository>.value(value: notifications),
        ChangeNotifierProvider<BuyerAccessViewModel>.value(
          value: accessViewModel,
        ),
        ChangeNotifierProvider<CatalogViewModel>(
          create: (context) => CatalogViewModel(
            context.read<CatalogRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
        ChangeNotifierProvider<BuyerOrdersViewModel>(
          create: (context) => BuyerOrdersViewModel(
            context.read<BuyerOrdersRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
        ChangeNotifierProvider<BuyerDeliveriesViewModel>(
          create: (context) => BuyerDeliveriesViewModel(
            context.read<BuyerDeliveriesRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
        ChangeNotifierProvider<BuyerRequestsViewModel>(
          create: (context) => BuyerRequestsViewModel(
            context.read<BuyerPurchaseWorkspaceRepository>(),
            context.read<PurchaseRequestRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
        ChangeNotifierProvider<BuyerCreditExposureViewModel>(
          create: (context) => BuyerCreditExposureViewModel(
            context.read<BuyerCreditExposureRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
        ChangeNotifierProvider<BuyerWalletViewModel>(
          create: (context) => BuyerWalletViewModel(
            context.read<BuyerWalletRepository>(),
            context.read<BuyerAccessRepository>(),
            context.read<BuyerWalletRechargeCommandStore>(),
            rechargeIntents: wallet,
            checkout: widget.walletCheckout,
          ),
        ),
        ChangeNotifierProvider<BusinessDocumentsViewModel>(
          create: (context) => BusinessDocumentsViewModel(
            context.read<BusinessDocumentsRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
        ChangeNotifierProvider<BuyerNotificationsViewModel>.value(
          value: notificationsViewModel,
        ),
      ],
      child: MaterialApp.router(
        title: 'Nexa Buyer',
        theme: NexaBuyerTheme.light,
        routerConfig: router,
      ),
    );
  }
}

GoRouter _createRouter(
  BuyerAccessViewModel access,
  BuyerPaymentsRepository payments,
  PaymentReportIdempotencyStore paymentIdempotencyStore,
  String mapTileUrlTemplate,
  String mapAttribution,
) => GoRouter(
  navigatorKey: _rootNavigatorKey,
  initialLocation: '/catalog',
  refreshListenable: access,
  redirect: (context, state) => BuyerWorkspaceNavigation.redirectLocation(
    access.snapshot,
    state.uri.path,
  ),
  routes: [
    GoRoute(path: '/sign-in', builder: (context, state) => const SignInPage()),
    GoRoute(
      path: '/choose-context',
      builder: (context, state) => const ContextSelectionPage(),
    ),
    GoRoute(
      path: '/workspace-unavailable',
      builder: (context, state) => const _WorkspaceUnavailablePage(),
    ),
    GoRoute(
      path: '/notifications',
      builder: (context, state) => const BuyerNotificationsPage(),
      routes: [
        GoRoute(
          path: 'preferences',
          builder: (context, state) => const BuyerNotificationPreferencesPage(),
        ),
      ],
    ),
    StatefulShellRoute.indexedStack(
      builder: (context, state, navigationShell) =>
          _BuyerNavigationShell(navigationShell: navigationShell),
      branches: [
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/catalog',
              builder: (context, state) => const CatalogPage(),
              routes: [
                GoRoute(
                  path: ':catalogItemId',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) {
                    final catalogItemId =
                        state.pathParameters['catalogItemId']!;
                    return ChangeNotifierProvider<CatalogItemDetailViewModel>(
                      create: (context) => CatalogItemDetailViewModel(
                        context.read<CatalogRepository>(),
                        context.read<BuyerAccessRepository>(),
                        catalogItemId,
                      )..load(),
                      child: const CatalogItemDetailPage(),
                    );
                  },
                  routes: [
                    GoRoute(
                      path: 'purchase-request',
                      parentNavigatorKey: _rootNavigatorKey,
                      builder: (context, state) {
                        final catalogItemId =
                            state.pathParameters['catalogItemId']!;
                        return ChangeNotifierProvider<
                          PurchaseRequestComposerViewModel
                        >(
                          create: (context) => PurchaseRequestComposerViewModel(
                            context.read<CatalogRepository>(),
                            context.read<PurchaseRequestRepository>(),
                            context.read<BuyerAccessRepository>(),
                            catalogItemId,
                            orderPaymentCapabilityQuery: context
                                .read<BuyerOrderPaymentCapabilityQuery>(),
                          )..load(),
                          child: const PurchaseRequestComposerPage(),
                        );
                      },
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/orders',
              builder: (context, state) => const BuyerOrdersPage(),
              routes: [
                GoRoute(
                  path: ':orderId',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) {
                    final orderId = state.pathParameters['orderId']!;
                    return ChangeNotifierProvider<BuyerOrderDetailViewModel>(
                      create: (context) => BuyerOrderDetailViewModel(
                        context.read<BuyerOrdersRepository>(),
                        context.read<BuyerAccessRepository>(),
                        orderId,
                      )..load(),
                      child: const BuyerOrderDetailPage(),
                    );
                  },
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/credit',
              builder: (context, state) => const BuyerCreditExposurePage(),
              routes: [
                GoRoute(
                  path: 'receivables/:receivableId/payments',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) {
                    final receivableId = state.pathParameters['receivableId']!;
                    final selected = state.extra;
                    final expectedAccountId =
                        selected is BuyerReceivableProjection
                        ? selected.clientAccountId
                        : null;
                    final receivableNumber =
                        selected is BuyerReceivableProjection
                        ? selected.number
                        : null;
                    return ChangeNotifierProvider<
                      BuyerPaymentActivityViewModel
                    >(
                      create: (context) => BuyerPaymentActivityViewModel(
                        payments,
                        context.read<BuyerAccessRepository>(),
                        paymentIdempotencyStore,
                        receivableId,
                        expectedClientAccountId: expectedAccountId,
                      ),
                      child: BuyerPaymentActivityPage(
                        receivableNumber: receivableNumber,
                      ),
                    );
                  },
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/documents',
              builder: (context, state) => const BusinessDocumentsPage(),
              routes: [
                GoRoute(
                  path: ':documentId',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) {
                    final documentId = state.pathParameters['documentId']!;
                    return ChangeNotifierProvider<
                      BusinessDocumentDetailViewModel
                    >(
                      create: (context) => BusinessDocumentDetailViewModel(
                        context.read<BusinessDocumentsRepository>(),
                        context.read<BuyerAccessRepository>(),
                        documentId,
                      )..load(),
                      child: const BusinessDocumentDetailPage(),
                    );
                  },
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/wallet',
              builder: (context, state) => const BuyerWalletPage(),
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/deliveries',
              builder: (context, state) => const BuyerDeliveriesPage(),
              routes: [
                GoRoute(
                  path: ':deliveryId',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) {
                    final deliveryId = state.pathParameters['deliveryId']!;
                    return ChangeNotifierProvider<BuyerDeliveryDetailViewModel>(
                      create: (context) => BuyerDeliveryDetailViewModel(
                        context.read<BuyerDeliveriesRepository>(),
                        context.read<BuyerAccessRepository>(),
                        deliveryId,
                        receiptStore: context
                            .read<BuyerReceiptIdempotencyStore>(),
                        evidenceStore: context
                            .read<BuyerReceiptEvidenceIdempotencyStore>(),
                      )..load(),
                      child: BuyerDeliveryDetailPage(
                        mapTileUrlTemplate: mapTileUrlTemplate,
                        mapAttribution: mapAttribution,
                      ),
                    );
                  },
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/requests',
              builder: (context, state) => const BuyerRequestsPage(),
              routes: [
                GoRoute(
                  path: 'drafts/:draftId',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) =>
                      ChangeNotifierProvider<BuyerDraftViewModel>(
                        create: (context) => BuyerDraftViewModel(
                          context.read<PurchaseRequestRepository>(),
                          context.read<BuyerPurchaseWorkspaceRepository>(),
                          context.read<BuyerAccessRepository>(),
                          context.read<BuyerOrderPaymentCapabilityQuery>(),
                          state.pathParameters['draftId']!,
                        )..load(),
                        child: const BuyerDraftPage(),
                      ),
                ),
                GoRoute(
                  path: ':requestId',
                  parentNavigatorKey: _rootNavigatorKey,
                  builder: (context, state) =>
                      ChangeNotifierProvider<BuyerRequestDetailViewModel>(
                        create: (context) => BuyerRequestDetailViewModel(
                          context.read<BuyerPurchaseWorkspaceRepository>(),
                          context.read<BuyerAccessRepository>(),
                          state.pathParameters['requestId']!,
                        )..load(),
                        child: const BuyerRequestDetailPage(),
                      ),
                ),
              ],
            ),
          ],
        ),
      ],
    ),
  ],
);

final _rootNavigatorKey = GlobalKey<NavigatorState>(
  debugLabel: 'buyer-root-navigator',
);

final class _BuyerNavigationShell extends StatelessWidget {
  const _BuyerNavigationShell({required this.navigationShell});
  final StatefulNavigationShell navigationShell;

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    final current = access.snapshot.currentContext;
    final options = BuyerWorkspaceNavigation.destinations(access.snapshot);
    const primaryOrder = [
      BuyerWorkspaceDestination.catalog,
      BuyerWorkspaceDestination.requests,
      BuyerWorkspaceDestination.orders,
      BuyerWorkspaceDestination.deliveries,
    ];
    final primary = primaryOrder.where(options.contains).toList();
    final secondary = options
        .where((option) => !primary.contains(option))
        .toList();
    final selected = primary.indexWhere(
      (option) => option.branchIndex == navigationShell.currentIndex,
    );
    final count = primary.length + (secondary.isEmpty ? 0 : 1);
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Image.asset(
              'assets/brand/nexa-mobile.png',
              width: 36,
              height: 36,
              semanticLabel: 'Nexa',
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Nexa Buyer'),
                  if (current != null)
                    Text(
                      current.tenantName,
                      style: Theme.of(context).textTheme.labelSmall,
                      overflow: TextOverflow.ellipsis,
                    ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          if (count < 2 && secondary.isNotEmpty)
            IconButton(
              tooltip: 'Opciones de compra',
              icon: const Icon(Icons.grid_view_outlined),
              onPressed: () => _more(context, secondary),
            ),
          if (BuyerWorkspaceNavigation.canReadNotifications(access.snapshot))
            IconButton(
              tooltip: 'Notificaciones',
              onPressed: () => context.push('/notifications'),
              icon: const Icon(Icons.notifications_outlined),
            ),
          IconButton(
            tooltip: 'Mi cuenta',
            icon: const Icon(Icons.account_circle_outlined),
            onPressed: () => _account(context, access),
          ),
        ],
      ),
      body: SafeArea(top: false, bottom: count < 2, child: navigationShell),
      bottomNavigationBar: count < 2
          ? null
          : NavigationBar(
              selectedIndex: selected >= 0
                  ? selected
                  : secondary.isNotEmpty
                  ? primary.length
                  : 0,
              onDestinationSelected: (index) {
                if (index == primary.length) {
                  _more(context, secondary);
                  return;
                }
                _open(primary[index]);
              },
              destinations: [
                ...primary.map(_navigationDestination),
                if (secondary.isNotEmpty)
                  const NavigationDestination(
                    icon: Icon(Icons.grid_view_outlined),
                    selectedIcon: Icon(Icons.grid_view),
                    label: 'Más',
                  ),
              ],
            ),
    );
  }

  void _open(BuyerWorkspaceDestination option) => navigationShell.goBranch(
    option.branchIndex,
    initialLocation: option.branchIndex == navigationShell.currentIndex,
  );

  Future<void> _more(
    BuildContext context,
    List<BuyerWorkspaceDestination> options,
  ) async {
    final access = context.read<BuyerAccessViewModel>();
    final openedAuthority = _authorityKey(access);
    final selected = await showModalBottomSheet<BuyerWorkspaceDestination>(
      context: context,
      showDragHandle: true,
      useSafeArea: true,
      builder: (context) => Consumer<BuyerAccessViewModel>(
        builder: (context, access, child) => SafeArea(
          top: false,
          child: SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Tu espacio de compra',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 16),
                  for (final option in options.where(
                    BuyerWorkspaceNavigation.destinations(access.snapshot)
                        .contains,
                  ))
                    ListTile(
                      leading: _navigationDestination(option).icon,
                      title: Text(option.label),
                      trailing: const Icon(Icons.chevron_right),
                      selected:
                          option.branchIndex == navigationShell.currentIndex,
                      onTap: () => Navigator.pop(context, option),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    if (selected != null &&
        context.mounted &&
        BuyerWorkspaceNavigation.destinations(
          context.read<BuyerAccessViewModel>().snapshot,
        ).contains(selected) &&
        openedAuthority ==
            _authorityKey(context.read<BuyerAccessViewModel>())) {
      _open(selected);
    }
  }

  Future<void> _account(
    BuildContext context,
    BuyerAccessViewModel access,
  ) async {
    final openedAuthority = _authorityKey(access);
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      useSafeArea: true,
      builder: (sheetContext) => Consumer<BuyerAccessViewModel>(
        builder: (sheetContext, access, child) {
          final current = access.snapshot.currentContext;
          final sameAuthority = openedAuthority == _authorityKey(access);
          return SafeArea(
            top: false,
            child: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      sameAuthority
                          ? current?.displayName ?? 'Mi cuenta Buyer'
                          : 'Mi cuenta Buyer',
                      style: Theme.of(sheetContext).textTheme.titleLarge,
                    ),
                    if (!sameAuthority)
                      const Padding(
                        padding: EdgeInsets.only(top: 12),
                        child: Text(
                          'El contexto de acceso cambió. Cierra este panel y vuelve a abrir Mi cuenta.',
                        ),
                      )
                    else ...[
                      if (current?.email != null) Text(current!.email!),
                      const SizedBox(height: 24),
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: const Icon(Icons.business_outlined),
                        title: Text(current?.tenantName ?? ''),
                        subtitle: Text(current?.workspaceName ?? ''),
                      ),
                    ],
                    const SizedBox(height: 16),
                    SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        onPressed: access.busy || !sameAuthority
                            ? null
                            : () {
                                Navigator.pop(sheetContext);
                                access.signOut();
                              },
                        icon: const Icon(Icons.logout),
                        label: const Text('Cerrar sesión'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

String _authorityKey(BuyerAccessViewModel access) =>
    '${access.snapshot.authorityEpoch}:${access.snapshot.currentContext?.authorityFingerprint}';

NavigationDestination _navigationDestination(
  BuyerWorkspaceDestination destination,
) => switch (destination) {
  BuyerWorkspaceDestination.requests => const NavigationDestination(
    icon: Icon(Icons.shopping_bag_outlined),
    selectedIcon: Icon(Icons.shopping_bag),
    label: 'Solicitudes',
  ),
  BuyerWorkspaceDestination.catalog => const NavigationDestination(
    icon: Icon(Icons.inventory_2_outlined),
    selectedIcon: Icon(Icons.inventory_2),
    label: 'Catálogo',
  ),
  BuyerWorkspaceDestination.orders => const NavigationDestination(
    icon: Icon(Icons.receipt_long_outlined),
    selectedIcon: Icon(Icons.receipt_long),
    label: 'Mis pedidos',
  ),
  BuyerWorkspaceDestination.credit => const NavigationDestination(
    icon: Icon(Icons.account_balance_wallet_outlined),
    selectedIcon: Icon(Icons.account_balance_wallet),
    label: 'Crédito y pagos',
  ),
  BuyerWorkspaceDestination.documents => const NavigationDestination(
    icon: Icon(Icons.description_outlined),
    selectedIcon: Icon(Icons.description),
    label: 'Documentos',
  ),
  BuyerWorkspaceDestination.wallet => const NavigationDestination(
    icon: Icon(Icons.account_balance_wallet_outlined),
    selectedIcon: Icon(Icons.account_balance_wallet),
    label: 'Billetera',
  ),
  BuyerWorkspaceDestination.deliveries => const NavigationDestination(
    icon: Icon(Icons.local_shipping_outlined),
    selectedIcon: Icon(Icons.local_shipping),
    label: 'Mis entregas',
  ),
};

final class _WorkspaceUnavailablePage extends StatelessWidget {
  const _WorkspaceUnavailablePage();

  @override
  Widget build(BuildContext context) {
    final access = context.watch<BuyerAccessViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('Espacio de trabajo')),
      body: SafeArea(
        top: false,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'Tu acceso actual no tiene opciones disponibles en este espacio.',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                if (BuyerWorkspaceNavigation.canReadNotifications(
                  access.snapshot,
                ))
                  OutlinedButton.icon(
                    onPressed: () => context.push('/notifications'),
                    icon: const Icon(Icons.notifications_outlined),
                    label: const Text('Ver notificaciones'),
                  ),
                TextButton(
                  onPressed: access.busy ? null : access.signOut,
                  child: const Text('Cerrar sesión'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

final class _ConfigurationApp extends StatelessWidget {
  const _ConfigurationApp();

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Nexa Buyer',
    theme: NexaBuyerTheme.light,
    home: Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.settings_ethernet, size: 40),
                const SizedBox(height: 16),
                Text(
                  'Configura el origen de Nexa API',
                  style: Theme.of(context).textTheme.titleLarge,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                const Text(
                  'Usa NEXA_API_BASE_URL con un origen HTTPS. Para desarrollo '
                  'local, ejecuta una compilación debug con un host local permitido.',
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
