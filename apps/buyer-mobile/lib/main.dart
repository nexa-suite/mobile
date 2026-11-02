import 'contexts/fulfillment_delivery/application/buyer_delivery_instructions_repository.dart';
import 'contexts/fulfillment_delivery/infrastructure/buyer_delivery_instructions_repository_impl.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_delivery_instructions_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_delivery_instructions_view_model.dart';
import 'contexts/business_documents/presentation/buyer_subject_documents_card.dart';
import 'contexts/business_documents/infrastructure/system_business_document_exporter.dart';
import 'contexts/sales_commitment/application/buyer_order_history_repository.dart';
import 'contexts/sales_commitment/presentation/buyer_order_history_card.dart';
import 'app/buyer_address_location_picker.dart';
import 'contexts/customer_buyer_relationships/application/buyer_addresses_repository.dart';
import 'contexts/customer_buyer_relationships/infrastructure/buyer_addresses_repository_impl.dart';
import 'contexts/customer_buyer_relationships/presentation/buyer_addresses_page.dart';
import 'contexts/customer_buyer_relationships/presentation/buyer_address_editor.dart';
import 'contexts/customer_buyer_relationships/presentation/buyer_addresses_view_model.dart';
import 'contexts/tenant_access_governance/application/buyer_profile_repository.dart';
import 'contexts/tenant_access_governance/infrastructure/buyer_profile_repository_impl.dart';
import 'contexts/tenant_access_governance/presentation/buyer_account_page.dart';
import 'contexts/tenant_access_governance/presentation/buyer_profile_page.dart';
import 'contexts/tenant_access_governance/presentation/buyer_profile_view_model.dart';

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';

import 'app/buyer_workspace_navigation.dart';
import 'app/buyer_shopping_summary.dart';
import 'app/buyer_delivery_qr_scanner.dart';
import 'contexts/business_documents/application/business_documents_repository.dart';
import 'contexts/business_documents/infrastructure/business_documents_repository_impl.dart';
import 'contexts/business_documents/presentation/business_document_detail_page.dart';
import 'contexts/business_documents/presentation/business_documents_page.dart';
import 'contexts/business_documents/presentation/business_documents_view_model.dart';
import 'contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'contexts/catalog_commercial_policy/infrastructure/catalog_repository_impl.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_item_detail_page.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_item_detail_view_model.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_page.dart';
import 'contexts/catalog_commercial_policy/presentation/catalog_view_model.dart';
import 'contexts/credit_receivables/application/credit_exposure_repository.dart';
import 'contexts/credit_receivables/infrastructure/buyer_credit_exposure_repository_impl.dart';
import 'contexts/credit_receivables/presentation/buyer_credit_exposure_page.dart';
import 'contexts/credit_receivables/presentation/buyer_credit_exposure_view_model.dart';
import 'contexts/customer_buyer_relationships/application/buyer_favorites_repository.dart';
import 'contexts/customer_buyer_relationships/infrastructure/buyer_favorites_repository_impl.dart';
import 'contexts/customer_buyer_relationships/presentation/buyer_favorites_page.dart';
import 'contexts/customer_buyer_relationships/presentation/buyer_favorites_view_model.dart';
import 'contexts/fulfillment_delivery/application/buyer_deliveries_repository.dart';
import 'contexts/fulfillment_delivery/application/buyer_handoff_receipt_repository.dart';
import 'contexts/fulfillment_delivery/application/buyer_receipt_evidence_idempotency_store.dart';
import 'contexts/fulfillment_delivery/application/buyer_receipt_idempotency_store.dart';
import 'contexts/fulfillment_delivery/infrastructure/buyer_deliveries_repository_impl.dart';
import 'contexts/fulfillment_delivery/infrastructure/buyer_handoff_receipt_repository_impl.dart';
import 'contexts/fulfillment_delivery/infrastructure/file_buyer_receipt_evidence_idempotency_store.dart';
import 'contexts/fulfillment_delivery/infrastructure/file_buyer_receipt_idempotency_store.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_deliveries_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_deliveries_view_model.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_delivery_detail_page.dart';
import 'contexts/fulfillment_delivery/presentation/buyer_handoff_receipt_view_model.dart';
import 'contexts/notifications/application/buyer_notifications_repository.dart';
import 'contexts/notifications/infrastructure/buyer_notifications_repository_impl.dart';
import 'contexts/notifications/presentation/buyer_notification_preferences_page.dart';
import 'contexts/notifications/presentation/buyer_notifications_page.dart';
import 'contexts/notifications/presentation/buyer_notifications_view_model.dart';
import 'contexts/payments/application/buyer_order_payment_capability_query.dart';
import 'contexts/payments/application/buyer_payments_repository.dart';
import 'contexts/payments/application/buyer_wallet_checkout_port.dart';
import 'contexts/payments/application/buyer_wallet_recharge_command_store.dart';
import 'contexts/payments/application/buyer_wallet_repository.dart';
import 'contexts/payments/application/payment_report_idempotency_store.dart';
import 'contexts/payments/infrastructure/buyer_payments_repository_impl.dart';
import 'contexts/payments/infrastructure/buyer_wallet_repository_impl.dart';
import 'contexts/payments/infrastructure/file_buyer_wallet_recharge_command_store.dart';
import 'contexts/payments/infrastructure/file_payment_report_idempotency_store.dart';
import 'contexts/payments/infrastructure/stripe_buyer_wallet_checkout.dart';
import 'contexts/payments/presentation/buyer_payment_activity_page.dart';
import 'contexts/payments/presentation/buyer_payment_activity_view_model.dart';
import 'contexts/payments/presentation/buyer_wallet_page.dart';
import 'contexts/payments/presentation/buyer_wallet_view_model.dart';
import 'contexts/sales_commitment/application/buyer_orders_repository.dart';
import 'contexts/sales_commitment/application/buyer_purchase_workspace_repository.dart';
import 'contexts/sales_commitment/application/purchase_request_repository.dart';
import 'contexts/sales_commitment/infrastructure/buyer_orders_repository_impl.dart';
import 'contexts/sales_commitment/infrastructure/file_purchase_request_idempotency_store.dart';
import 'contexts/sales_commitment/infrastructure/purchase_request_repository_impl.dart';
import 'contexts/sales_commitment/presentation/buyer_draft_page.dart';
import 'contexts/sales_commitment/presentation/buyer_draft_view_model.dart';
import 'contexts/sales_commitment/presentation/buyer_order_detail_page.dart';
import 'contexts/sales_commitment/presentation/buyer_orders_page.dart';
import 'contexts/sales_commitment/presentation/buyer_orders_view_model.dart';
import 'contexts/sales_commitment/presentation/buyer_request_detail_page.dart';
import 'contexts/sales_commitment/presentation/buyer_requests_page.dart';
import 'contexts/sales_commitment/presentation/buyer_requests_view_model.dart';
import 'contexts/sales_commitment/presentation/purchase_request_composer_page.dart';
import 'contexts/sales_commitment/presentation/purchase_request_composer_view_model.dart';
import 'contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'contexts/tenant_access_governance/application/buyer_change_feed_repository.dart';
import 'contexts/tenant_access_governance/infrastructure/buyer_access_repository_impl.dart';
import 'contexts/tenant_access_governance/infrastructure/buyer_change_feed_repository_impl.dart';
import 'contexts/tenant_access_governance/infrastructure/local_buyer_biometric_authenticator.dart';
import 'contexts/tenant_access_governance/presentation/buyer_access_view_model.dart';
import 'contexts/tenant_access_governance/presentation/buyer_password_recovery_view_model.dart';
import 'contexts/tenant_access_governance/presentation/buyer_password_reset_page.dart';
import 'contexts/tenant_access_governance/presentation/context_selection_page.dart';
import 'contexts/tenant_access_governance/presentation/sign_in_page.dart';
import 'contexts/tenant_access_governance/application/buyer_password_recovery_repository.dart'
    as password_recovery;
import 'contexts/tenant_access_governance/infrastructure/buyer_password_recovery_repository_impl.dart'
    as password_recovery;
import 'core/configuration/buyer_map_configuration.dart';
import 'core/network/nexa_api_client.dart';
import 'core/presentation/nexa_buyer_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final walletCheckout = await StripeBuyerWalletCheckout.configure(
    const String.fromEnvironment('NEXA_STRIPE_PUBLISHABLE_KEY'),
  );
  final mapConfiguration = BuyerMapConfiguration.resolve(
    tileUrlTemplate: String.fromEnvironment('NEXA_MAP_TILE_URL_TEMPLATE'),
    attribution: String.fromEnvironment('NEXA_MAP_ATTRIBUTION'),
    isDebugBuild: kDebugMode,
  );
  runApp(
    BuyerMobileApp(
      apiBaseUrl: const String.fromEnvironment('NEXA_API_BASE_URL'),
      allowLocalHttp: kDebugMode,
      walletCheckout: walletCheckout,
      mapTileUrlTemplate: mapConfiguration.tileUrlTemplate,
      mapAttribution: mapConfiguration.attribution,
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
  BuyerDeliveryInstructionsRepository? _deliveryInstructionsRepository;
  BuyerFavoritesRepository? _favoritesRepository;
  BuyerProfileRepository? _profileRepository;
  BuyerAddressesRepository? _addressesRepository;
  BuyerAccessRepositoryImpl? _accessRepository;
  BuyerChangeFeedRepositoryImpl? _buyerChangeFeedRepository;
  BuyerAccessViewModel? _accessViewModel;
  password_recovery.BuyerPasswordRecoveryRepositoryImpl?
  _passwordRecoveryRepository;
  BuyerPasswordRecoveryViewModel? _passwordRecoveryViewModel;
  CatalogRepositoryImpl? _catalogRepository;
  BuyerCreditExposureRepositoryImpl? _creditExposureRepository;
  BuyerPaymentsRepositoryImpl? _paymentsRepository;
  BuyerWalletRepositoryImpl? _walletRepository;
  FileBuyerWalletRechargeCommandStore? _walletRechargeCommandStore;
  FilePaymentReportIdempotencyStore? _paymentIdempotencyStore;
  BuyerHandoffReceiptRepositoryImpl? _handoffReceiptRepository;
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
      _deliveryInstructionsRepository = BuyerDeliveryInstructionsRepositoryImpl(
        api,
      );
      _favoritesRepository = BuyerFavoritesRepositoryImpl(api);
      _profileRepository = BuyerProfileRepositoryImpl(api);
      _addressesRepository = BuyerAddressesRepositoryImpl(api);
      api.sessionCredentials = _accessRepository;
      _buyerChangeFeedRepository = BuyerChangeFeedRepositoryImpl(
        api,
        _accessRepository!,
      );
      _passwordRecoveryRepository =
          password_recovery.BuyerPasswordRecoveryRepositoryImpl(api);
      _passwordRecoveryViewModel = BuyerPasswordRecoveryViewModel(
        _passwordRecoveryRepository!,
      );
      _accessViewModel = BuyerAccessViewModel(
        _accessRepository!,
        biometricAuthenticator: LocalBuyerBiometricAuthenticator(),
      );
      _catalogRepository = CatalogRepositoryImpl(api);
      _creditExposureRepository = BuyerCreditExposureRepositoryImpl(api);
      _paymentsRepository = BuyerPaymentsRepositoryImpl(api);
      _walletRepository = BuyerWalletRepositoryImpl(api);
      _walletRechargeCommandStore = FileBuyerWalletRechargeCommandStore();
      _paymentIdempotencyStore = FilePaymentReportIdempotencyStore();
      _handoffReceiptRepository = BuyerHandoffReceiptRepositoryImpl(api);
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
    _passwordRecoveryViewModel?.dispose();
    _accessViewModel?.dispose();
    _accessRepository?.dispose();
    _httpClient?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final access = _accessRepository;
    final changeFeed = _buyerChangeFeedRepository;
    final accessViewModel = _accessViewModel;
    final passwordRecoveryRepository = _passwordRecoveryRepository;
    final passwordRecoveryViewModel = _passwordRecoveryViewModel;
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
        changeFeed == null ||
        accessViewModel == null ||
        passwordRecoveryRepository == null ||
        passwordRecoveryViewModel == null ||
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
        Provider<BuyerDeliveryInstructionsRepository>.value(
          value: _deliveryInstructionsRepository!,
        ),
        Provider<BuyerAccessRepository>.value(value: access),
        Provider<BuyerChangeFeedRepository>.value(value: changeFeed),
        Provider<password_recovery.BuyerPasswordRecoveryRepository>.value(
          value: passwordRecoveryRepository,
        ),
        Provider<CatalogRepository>.value(value: catalog),
        ChangeNotifierProvider<BuyerProfileViewModel>(
          create: (_) => BuyerProfileViewModel(_profileRepository!, access),
        ),
        ChangeNotifierProvider<BuyerAddressesViewModel>(
          create: (_) => BuyerAddressesViewModel(_addressesRepository!, access),
        ),
        Provider<BuyerFavoritesRepository>.value(value: _favoritesRepository!),
        ChangeNotifierProvider<BuyerFavoritesViewModel>(
          create: (context) => BuyerFavoritesViewModel(
            context.read<BuyerFavoritesRepository>(),
            access,
          ),
        ),
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
        Provider<BuyerHandoffReceiptRepository>.value(
          value: _handoffReceiptRepository!,
        ),
        Provider<BuyerDeliveriesRepository>.value(value: deliveries),
        Provider<BuyerReceiptIdempotencyStore>.value(
          value: buyerReceiptIdempotencyStore,
        ),
        Provider<BuyerReceiptEvidenceIdempotencyStore>.value(
          value: buyerReceiptEvidenceIdempotencyStore,
        ),
        Provider<BuyerOrdersRepository>.value(value: orders),
        Provider<BuyerOrderHistoryRepository>.value(value: orders),
        Provider<PurchaseRequestRepository>.value(value: purchaseRequests),
        Provider<BuyerPurchaseWorkspaceRepository>.value(
          value: purchaseRequests,
        ),
        Provider<BusinessDocumentsRepository>.value(value: businessDocuments),
        Provider<BuyerNotificationsRepository>.value(value: notifications),
        ChangeNotifierProvider<BuyerAccessViewModel>.value(
          value: accessViewModel,
        ),
        ChangeNotifierProvider<BuyerPasswordRecoveryViewModel>.value(
          value: passwordRecoveryViewModel,
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
        debugShowCheckedModeBanner: false,
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
  initialLocation: '/home',
  refreshListenable: access,
  redirect: (context, state) => BuyerWorkspaceNavigation.redirectLocation(
    access.snapshot,
    state.uri.path,
  ),
  routes: [
    GoRoute(
      path: '/account',
      builder: (context, state) => const BuyerAccountPage(),
    ),
    GoRoute(
      path: '/profile',
      builder: (context, state) => const BuyerProfilePage(),
    ),
    GoRoute(
      path: '/addresses',
      builder: (context, state) => BuyerAddressesPage(
        chooseLocation: mapTileUrlTemplate.isEmpty || mapAttribution.isEmpty
            ? null
            : (context, initial) =>
                  Navigator.of(context).push<BuyerAddressLocation>(
                    MaterialPageRoute(
                      builder: (_) => BuyerAddressLocationPickerPage(
                        tileUrlTemplate: mapTileUrlTemplate,
                        attribution: mapAttribution,
                        initial: initial,
                      ),
                    ),
                  ),
      ),
    ),
    GoRoute(
      path: '/orders/:orderId/instructions',
      builder: (context, state) => ChangeNotifierProvider(
        create: (context) => BuyerDeliveryInstructionsViewModel(
          context.read<BuyerDeliveryInstructionsRepository>(),
          context.read<BuyerAccessRepository>(),
          state.pathParameters['orderId']!,
        ),
        child: const BuyerDeliveryInstructionsPage(),
      ),
    ),
    GoRoute(path: '/sign-in', builder: (context, state) => const SignInPage()),
    GoRoute(
      path: '/forgot-password',
      builder: (context, state) => SignInPage(
        startInRecovery: true,
        initialEmail: state.uri.queryParameters['email'] ?? '',
      ),
    ),
    GoRoute(
      path: '/reset-password',
      builder: (context, state) => BuyerPasswordResetPage(
        token: state.uri.queryParameters['token'] ?? '',
      ),
    ),
    GoRoute(
      path: '/favorites',
      builder: (context, state) => const BuyerFavoritesPage(),
    ),
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
              path: '/home',
              builder: (context, state) => const BuyerShoppingSummary(),
            ),
          ],
        ),
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
                      child: CatalogItemDetailPage(
                        favoriteActionBuilder: (item) =>
                            BuyerSaveFavoriteButton(
                              sellableSkuId: item.sellableSkuId,
                              routePath: state.uri.path,
                            ),
                      ),
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
                      child: BuyerOrderDetailPage(
                        history: BuyerOrderHistoryCard(orderId: orderId),
                        onOpenInstructions: () => context.push(
                          '/orders/${Uri.encodeComponent(orderId)}/instructions',
                        ),
                        documents: BuyerSubjectDocumentsCard(
                          subjectType: 'SALES_ORDER',
                          subjectId: orderId,
                          visibleRoute:
                              '/orders/${Uri.encodeComponent(orderId)}',
                        ),
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
                      child: BusinessDocumentDetailPage(
                        exporter: SystemBusinessDocumentExporter(),
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
                      child:
                          ChangeNotifierProvider<BuyerHandoffReceiptViewModel>(
                            create: (context) => BuyerHandoffReceiptViewModel(
                              context.read<BuyerHandoffReceiptRepository>(),
                              context.read<BuyerAccessRepository>(),
                              deliveryId,
                            ),
                            child: BuyerDeliveryDetailPage(
                              scanDeliveryToken: () => Navigator.of(context)
                                  .push<String>(
                                    MaterialPageRoute(
                                      builder: (_) =>
                                          const BuyerDeliveryQrScanner(),
                                    ),
                                  ),
                              mapTileUrlTemplate: mapTileUrlTemplate,
                              mapAttribution: mapAttribution,
                            ),
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
    final orderOptions = [
      BuyerWorkspaceDestination.requests,
      BuyerWorkspaceDestination.orders,
      BuyerWorkspaceDestination.deliveries,
    ].where(options.contains).toList();
    final tabs = <BuyerWorkspaceDestination>[
      if (options.contains(BuyerWorkspaceDestination.home))
        BuyerWorkspaceDestination.home,
      if (options.contains(BuyerWorkspaceDestination.catalog))
        BuyerWorkspaceDestination.catalog,
      if (orderOptions.isNotEmpty) orderOptions.first,
    ];
    final inOrders = orderOptions.any(
      (item) => item.branchIndex == navigationShell.currentIndex,
    );
    final selected = inOrders
        ? tabs.indexOf(orderOptions.first)
        : tabs.indexWhere(
            (item) => item.branchIndex == navigationShell.currentIndex,
          );
    final selectedIndex = selected < 0 ? -1 : selected;
    NavigationDestination destination(BuyerWorkspaceDestination item) {
      if (orderOptions.contains(item)) {
        return const NavigationDestination(
          icon: Icon(Icons.shopping_bag_outlined),
          selectedIcon: Icon(Icons.shopping_bag),
          label: 'Pedidos',
        );
      }
      return _navigationDestination(item);
    }

    void select(int index) {
      final item = tabs[index];
      if (!orderOptions.contains(item) || !inOrders) _open(item);
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final hasPrimaryNavigation = tabs.length > 1 && selectedIndex >= 0;
        final useRail = constraints.maxWidth >= 800 && hasPrimaryNavigation;
        return Scaffold(
          appBar: AppBar(
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Tus compras'),
                if (current != null)
                  Text(
                    current.tenantName,
                    style: Theme.of(context).textTheme.labelSmall,
                    overflow: TextOverflow.ellipsis,
                  ),
              ],
            ),
            actions: [
              if (BuyerWorkspaceNavigation.canReadNotifications(
                access.snapshot,
              ))
                IconButton(
                  tooltip: 'Notificaciones',
                  onPressed: () => context.push('/notifications'),
                  icon: const Icon(Icons.notifications_outlined),
                ),
              IconButton(
                tooltip: 'Cuenta',
                icon: CircleAvatar(
                  radius: 17,
                  backgroundColor: Theme.of(context)
                      .colorScheme
                      .primaryContainer,
                  child: Text(
                    _accountInitial(current?.displayName, current?.email),
                    style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onPrimaryContainer,
                    ),
                  ),
                ),
                onPressed: () => context.push('/account'),
              ),
            ],
          ),
          body: SafeArea(
            top: false,
            bottom: useRail,
            child: Row(
              children: [
                if (useRail) ...[
                  NavigationRail(
                    selectedIndex: selectedIndex,
                    labelType: NavigationRailLabelType.all,
                    onDestinationSelected: select,
                    destinations: tabs.map((item) {
                      final tab = destination(item);
                      return NavigationRailDestination(
                        icon: tab.icon,
                        selectedIcon: tab.selectedIcon,
                        label: Text(tab.label),
                      );
                    }).toList(),
                  ),
                  const VerticalDivider(width: 1),
                ],
                Expanded(
                  child: Column(
                    children: [
                      if (inOrders && orderOptions.length > 1)
                        SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 8,
                          ),
                          child: Row(
                            children: [
                              for (final item in orderOptions)
                                Padding(
                                  padding: const EdgeInsets.only(right: 8),
                                  child: ChoiceChip(
                                    selected:
                                        item.branchIndex ==
                                        navigationShell.currentIndex,
                                    label: Text(switch (item) {
                                      BuyerWorkspaceDestination.orders =>
                                        'Órdenes',
                                      BuyerWorkspaceDestination.deliveries =>
                                        'Seguimiento',
                                      _ => item.label,
                                    }),
                                    onSelected: (_) => _open(item),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      Expanded(child: navigationShell),
                    ],
                  ),
                ),
              ],
            ),
          ),
          bottomNavigationBar: useRail || !hasPrimaryNavigation
              ? null
              : NavigationBar(
                  selectedIndex: selectedIndex,
                  onDestinationSelected: select,
                  destinations: tabs.map(destination).toList(),
                ),
        );
      },
    );
  }

  void _open(BuyerWorkspaceDestination option) => navigationShell.goBranch(
    option.branchIndex,
    initialLocation: option.branchIndex == navigationShell.currentIndex,
  );
}

String _accountInitial(String? displayName, String? email) {
  final name = displayName?.trim() ?? '';
  final fallback = email?.trim() ?? '';
  final value = name.isNotEmpty ? name : fallback;
  return value.isEmpty
      ? 'N'
      : String.fromCharCode(value.runes.first).toUpperCase();
}

NavigationDestination _navigationDestination(
  BuyerWorkspaceDestination destination,
) => switch (destination) {
  BuyerWorkspaceDestination.home => const NavigationDestination(
    icon: Icon(Icons.home_outlined),
    selectedIcon: Icon(Icons.home),
    label: 'Inicio',
  ),
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
