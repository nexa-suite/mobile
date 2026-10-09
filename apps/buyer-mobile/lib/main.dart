import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';

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
import 'contexts/payments/application/buyer_payments_repository.dart';
import 'contexts/payments/application/buyer_wallet_repository.dart';
import 'contexts/payments/application/payment_report_idempotency_store.dart';
import 'contexts/payments/infrastructure/buyer_payments_repository_impl.dart';
import 'contexts/payments/infrastructure/buyer_wallet_repository_impl.dart';
import 'contexts/payments/infrastructure/file_payment_report_idempotency_store.dart';
import 'contexts/payments/presentation/buyer_payment_activity_page.dart';
import 'contexts/payments/presentation/buyer_payment_activity_view_model.dart';
import 'contexts/payments/presentation/buyer_wallet_page.dart';
import 'contexts/payments/presentation/buyer_wallet_view_model.dart';
import 'contexts/fulfillment_delivery/infrastructure/buyer_deliveries_repository_impl.dart';
import 'contexts/fulfillment_delivery/application/buyer_deliveries_repository.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_deliveries_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_delivery_detail_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_deliveries_view_model.dart';
import 'contexts/sales_commitment/application/buyer_orders_repository.dart';
import 'contexts/sales_commitment/application/purchase_request_repository.dart';
import 'contexts/sales_commitment/infrastructure/buyer_orders_repository_impl.dart';
import 'contexts/sales_commitment/infrastructure/file_purchase_request_idempotency_store.dart';
import 'contexts/sales_commitment/infrastructure/purchase_request_repository_impl.dart';
import 'contexts/sales_commitment/presentation/buyer_order_detail_page.dart';
import 'contexts/sales_commitment/presentation/buyer_orders_page.dart';
import 'contexts/sales_commitment/presentation/buyer_orders_view_model.dart';
import 'contexts/sales_commitment/presentation/purchase_request_composer_page.dart';
import 'contexts/sales_commitment/presentation/purchase_request_composer_view_model.dart';
import 'contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'contexts/tenant_access_governance/infrastructure/buyer_access_repository_impl.dart';
import 'contexts/tenant_access_governance/presentation/buyer_access_view_model.dart';
import 'contexts/tenant_access_governance/presentation/context_selection_page.dart';
import 'contexts/tenant_access_governance/presentation/sign_in_page.dart';
import 'core/network/nexa_api_client.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    BuyerMobileApp(
      apiBaseUrl: const String.fromEnvironment('NEXA_API_BASE_URL'),
      allowLocalHttp: kDebugMode,
    ),
  );
}

final class BuyerMobileApp extends StatefulWidget {
  const BuyerMobileApp({
    super.key,
    required this.apiBaseUrl,
    required this.allowLocalHttp,
    this.httpClient,
  });

  final String apiBaseUrl;
  final bool allowLocalHttp;
  final http.Client? httpClient;

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
  FilePaymentReportIdempotencyStore? _paymentIdempotencyStore;
  BuyerDeliveriesRepositoryImpl? _deliveriesRepository;
  BuyerOrdersRepositoryImpl? _ordersRepository;
  PurchaseRequestRepositoryImpl? _purchaseRequestRepository;
  BusinessDocumentsRepositoryImpl? _businessDocumentsRepository;
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
      _paymentIdempotencyStore = FilePaymentReportIdempotencyStore();
      _deliveriesRepository = BuyerDeliveriesRepositoryImpl(api);
      _ordersRepository = BuyerOrdersRepositoryImpl(api);
      _purchaseRequestRepository = PurchaseRequestRepositoryImpl(
        api,
        FilePurchaseRequestIdempotencyStore(),
      );
      _businessDocumentsRepository = BusinessDocumentsRepositoryImpl(api);
      _router = _createRouter(
        _accessViewModel!,
        _paymentsRepository!,
        _paymentIdempotencyStore!,
      );
    } on FormatException {
      _configurationInvalid = true;
    }
  }

  @override
  void dispose() {
    _router?.dispose();
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
    final paymentIdempotencyStore = _paymentIdempotencyStore;
    final deliveries = _deliveriesRepository;
    final orders = _ordersRepository;
    final purchaseRequests = _purchaseRequestRepository;
    final businessDocuments = _businessDocumentsRepository;
    final router = _router;
    if (_configurationInvalid ||
        access == null ||
        accessViewModel == null ||
        catalog == null ||
        creditExposure == null ||
        payments == null ||
        wallet == null ||
        paymentIdempotencyStore == null ||
        deliveries == null ||
        orders == null ||
        purchaseRequests == null ||
        businessDocuments == null ||
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
        Provider<PaymentReportIdempotencyStore>.value(
          value: paymentIdempotencyStore,
        ),
        Provider<BuyerDeliveriesRepository>.value(value: deliveries),
        Provider<BuyerOrdersRepository>.value(value: orders),
        Provider<PurchaseRequestRepository>.value(value: purchaseRequests),
        Provider<BusinessDocumentsRepository>.value(value: businessDocuments),
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
          ),
        ),
        ChangeNotifierProvider<BusinessDocumentsViewModel>(
          create: (context) => BusinessDocumentsViewModel(
            context.read<BusinessDocumentsRepository>(),
            context.read<BuyerAccessRepository>(),
          ),
        ),
      ],
      child: MaterialApp.router(
        title: 'Nexa Buyer',
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF176B61)),
          useMaterial3: true,
          inputDecorationTheme: const InputDecorationTheme(
            border: OutlineInputBorder(),
          ),
        ),
        routerConfig: router,
      ),
    );
  }
}

GoRouter _createRouter(
  BuyerAccessViewModel access,
  BuyerPaymentsRepository payments,
  PaymentReportIdempotencyStore paymentIdempotencyStore,
) => GoRouter(
  navigatorKey: _rootNavigatorKey,
  initialLocation: '/catalog',
  refreshListenable: access,
  redirect: (context, state) {
    final status = access.snapshot.status;
    final path = state.uri.path;
    if (status == BuyerAccessStatus.signedOut && path != '/sign-in') {
      return '/sign-in';
    }
    if (status == BuyerAccessStatus.choosingContext &&
        path != '/choose-context') {
      return '/choose-context';
    }
    if (status == BuyerAccessStatus.signedIn &&
        (path == '/sign-in' || path == '/choose-context')) {
      return '/catalog';
    }
    if (status == BuyerAccessStatus.signedIn &&
        (path == '/deliveries' || path.startsWith('/deliveries/')) &&
        !(access.snapshot.currentContext?.permissions.contains(
              BuyerDeliveriesViewModel.readPermission,
            ) ??
            false)) {
      return '/orders';
    }
    if (status == BuyerAccessStatus.signedIn &&
        path.startsWith('/credit/receivables/') &&
        !(access.snapshot.currentContext?.permissions.contains(
              BuyerPaymentActivityViewModel.readPermission,
            ) ??
            false) &&
        !(access.snapshot.currentContext?.permissions.contains(
              BuyerPaymentActivityViewModel.createPermission,
            ) ??
            false)) {
      return '/credit';
    }
    if (status == BuyerAccessStatus.signedIn &&
        path == '/wallet' &&
        (!(access.snapshot.currentContext?.roles.contains(
                  BuyerWalletViewModel.buyerRole,
                ) ??
                false) ||
            !(access.snapshot.currentContext?.permissions.contains(
                  BuyerWalletViewModel.readPermission,
                ) ??
                false))) {
      return '/credit';
    }
    return null;
  },
  routes: [
    GoRoute(path: '/sign-in', builder: (context, state) => const SignInPage()),
    GoRoute(
      path: '/choose-context',
      builder: (context, state) => const ContextSelectionPage(),
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
                      )..load(),
                      child: const BuyerDeliveryDetailPage(),
                    );
                  },
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
    final destinations = <NavigationDestination>[
      const NavigationDestination(
        icon: Icon(Icons.inventory_2_outlined),
        selectedIcon: Icon(Icons.inventory_2),
        label: 'Catálogo',
      ),
      const NavigationDestination(
        icon: Icon(Icons.receipt_long_outlined),
        selectedIcon: Icon(Icons.receipt_long),
        label: 'Mis pedidos',
      ),
      const NavigationDestination(
        icon: Icon(Icons.account_balance_wallet_outlined),
        selectedIcon: Icon(Icons.account_balance_wallet),
        label: 'Crédito y pagos',
      ),
      const NavigationDestination(
        icon: Icon(Icons.description_outlined),
        selectedIcon: Icon(Icons.description),
        label: 'Documentos',
      ),
    ];
    final branchIndices = <int>[0, 1, 2, 3];
    if (current?.roles.contains(BuyerWalletViewModel.buyerRole) == true &&
        current?.permissions.contains(BuyerWalletViewModel.readPermission) ==
            true) {
      destinations.add(
        const NavigationDestination(
          icon: Icon(Icons.account_balance_wallet_outlined),
          selectedIcon: Icon(Icons.account_balance_wallet),
          label: 'Billetera',
        ),
      );
      branchIndices.add(4);
    }
    if (current?.permissions.contains(
          BuyerDeliveriesViewModel.readPermission,
        ) ??
        false) {
      destinations.add(
        const NavigationDestination(
          icon: Icon(Icons.local_shipping_outlined),
          selectedIcon: Icon(Icons.local_shipping),
          label: 'Mis entregas',
        ),
      );
      branchIndices.add(5);
    }
    final visibleSelectedIndex = branchIndices.indexOf(
      navigationShell.currentIndex,
    );
    final selectedIndex = visibleSelectedIndex >= 0 ? visibleSelectedIndex : 0;
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Nexa Buyer'),
            if (current != null)
              Text(
                '${current.tenantName} · ${current.workspaceName}',
                style: Theme.of(context).textTheme.labelSmall,
                overflow: TextOverflow.ellipsis,
              ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: 'Cerrar sesión',
            onPressed: access.busy ? null : access.signOut,
            icon: const Icon(Icons.logout),
          ),
        ],
      ),
      body: navigationShell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: selectedIndex,
        onDestinationSelected: (index) {
          final branchIndex = branchIndices[index];
          navigationShell.goBranch(
            branchIndex,
            initialLocation: branchIndex == navigationShell.currentIndex,
          );
        },
        destinations: destinations,
      ),
    );
  }
}

final class _ConfigurationApp extends StatelessWidget {
  const _ConfigurationApp();

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Nexa Buyer',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF176B61)),
      useMaterial3: true,
    ),
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
