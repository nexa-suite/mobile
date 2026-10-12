import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:nexa_buyer_mobile/contexts/fulfillment_delivery/infrastructure/buyer_deliveries_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/business_documents/infrastructure/business_documents_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/infrastructure/catalog_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/credit_receivables/infrastructure/buyer_credit_exposure_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/infrastructure/buyer_orders_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/infrastructure/file_purchase_request_idempotency_store.dart';
import 'package:nexa_buyer_mobile/contexts/sales_commitment/infrastructure/purchase_request_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/infrastructure/buyer_access_repository_impl.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _runIntegration = bool.fromEnvironment('NEXA_RUN_LOCAL_API_INTEGRATION');
const _apiOrigin = String.fromEnvironment(
  'NEXA_API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);

void main() {
  test(
    'reads Buyer delivery pages and one selected detail from the local API',
    () async {
      final environment = Platform.environment;
      final identifier = environment['NEXA_DEV_BUYER_EMAIL'];
      final password = environment['NEXA_DEV_BUYER_PASSWORD'];
      final tenantSlug = environment['NEXA_DEV_TENANT_SLUG'];
      final workspaceSlug = environment['NEXA_DEV_WORKSPACE_SLUG'];
      expect(identifier, isNotNull);
      expect(password, isNotNull);
      expect(tenantSlug, isNotNull);
      expect(workspaceSlug, isNotNull);
      final origin = NexaApiOrigin.parse(_apiOrigin, allowLocalHttp: true);
      expect(
        {'localhost', '127.0.0.1', '::1'}.contains(origin.uri.host),
        isTrue,
        reason: 'This integration test must target the local Docker API.',
      );

      final api = NexaApiClient(origin: origin, httpClient: http.Client());
      final access = BuyerAccessRepositoryImpl(api);
      api.sessionCredentials = access;
      try {
        await access.signIn(identifier: identifier!, password: password!);
        if (access.snapshot.status == BuyerAccessStatus.choosingContext) {
          final contexts = access.snapshot.availableContexts
              .where(
                (context) =>
                    context.roles.contains('BUYER') &&
                    context.tenantSlug == tenantSlug &&
                    context.workspaceSlug == workspaceSlug,
              )
              .toList(growable: false);
          expect(contexts, hasLength(1));
          await access.selectContext(contexts.single.membershipId);
        }
        final context = access.snapshot.currentContext;
        expect(access.snapshot.isSignedIn, isTrue);
        expect(context, isNotNull);
        expect(context!.permissions, contains('buyer.tracking.read'));

        final repository = BuyerDeliveriesRepositoryImpl(api);
        final page = await repository.list(page: 0);
        expect(page.page, 0);
        expect(page.items.length, lessThanOrEqualTo(page.size));
        expect(page.total, greaterThanOrEqualTo(page.items.length));
        if (page.items.isEmpty) {
          stdout.writeln(
            'LOCAL_API_BUYER_DELIVERIES=0; DETAIL_EVENTS=SKIPPED_NO_FIXTURE_DELIVERY',
          );
        } else {
          final selected = page.items.first;
          final detail = await repository.detail(selected.id);
          expect(detail.delivery.id, selected.id);
          expect(detail.delivery.salesOrderNumber, isNotEmpty);
          expect(detail.delivery.status, isNotEmpty);
          expect(
            detail.events.every(
              (event) => event.type.isNotEmpty && event.occurredAt.isUtc,
            ),
            isTrue,
          );
          stdout.writeln(
            'LOCAL_API_BUYER_DELIVERIES=${page.items.length}; DETAIL_EVENTS=${detail.events.length}',
          );
        }
      } finally {
        await access.signOut();
        await access.dispose();
        api.close();
      }
    },
    skip: _runIntegration ? false : 'Set NEXA_RUN_LOCAL_API_INTEGRATION=true to use the local fixture API.',
  );

  test(
    'reads scoped Buyer drafts from the local API without creating a request',
    () async {
      final environment = Platform.environment;
      final identifier = environment['NEXA_DEV_BUYER_EMAIL'];
      final password = environment['NEXA_DEV_BUYER_PASSWORD'];
      final tenantSlug = environment['NEXA_DEV_TENANT_SLUG'];
      final workspaceSlug = environment['NEXA_DEV_WORKSPACE_SLUG'];
      expect(identifier, isNotNull);
      expect(password, isNotNull);
      expect(tenantSlug, isNotNull);
      expect(workspaceSlug, isNotNull);
      final origin = NexaApiOrigin.parse(_apiOrigin, allowLocalHttp: true);
      expect(
        {'localhost', '127.0.0.1', '::1'}.contains(origin.uri.host),
        isTrue,
      );

      final api = NexaApiClient(origin: origin, httpClient: http.Client());
      final access = BuyerAccessRepositoryImpl(api);
      api.sessionCredentials = access;
      Directory? commandDirectory;
      try {
        await access.signIn(identifier: identifier!, password: password!);
        if (access.snapshot.status == BuyerAccessStatus.choosingContext) {
          final contexts = access.snapshot.availableContexts
              .where(
                (context) =>
                    context.roles.contains('BUYER') &&
                    context.tenantSlug == tenantSlug &&
                    context.workspaceSlug == workspaceSlug,
              )
              .toList(growable: false);
          expect(contexts, hasLength(1));
          await access.selectContext(contexts.single.membershipId);
        }
        expect(access.snapshot.isSignedIn, isTrue);
        expect(access.snapshot.currentContext?.roles, contains('BUYER'));

        final repository = PurchaseRequestRepositoryImpl(
          api,
          FilePurchaseRequestIdempotencyStore(
            directoryProvider: () async {
              commandDirectory ??= await Directory.systemTemp.createTemp(
                'nexa-buyer-draft-list-test-',
              );
              return commandDirectory!;
            },
          ),
        );
        final result = await repository.listDrafts(page: 0);
        expect(result.page, 0);
        expect(result.items.length, lessThanOrEqualTo(result.size));
        expect(result.totalItems, greaterThanOrEqualTo(result.items.length));
        expect(
          result.items.map((draft) => draft.id).toSet().length,
          result.items.length,
        );
        final catalog = await CatalogRepositoryImpl(api)
            .list(query: '', page: 0);
        final credit = await BuyerCreditExposureRepositoryImpl(api)
            .readCurrentBuyerExposure(currency: 'PEN');
        final orders = await BuyerOrdersRepositoryImpl(api).list(page: 0);
        final documents = await BusinessDocumentsRepositoryImpl(api)
            .list(page: 0);
        expect(credit.currency, 'PEN');
        stdout.writeln('LOCAL_API_READ_ONLY_SCOPES=PASS');
        stdout.writeln(
          'LOCAL_API_PAGE_COUNTS catalog=${catalog.items.length} drafts=${result.items.length} orders=${orders.items.length} documents=${documents.items.length}',
        );
      } finally {
        await access.signOut();
        await access.dispose();
        api.close();
        if (commandDirectory != null && await commandDirectory!.exists()) {
          await commandDirectory!.delete(recursive: true);
        }
      }
    },
    skip: _runIntegration ? false : 'Set NEXA_RUN_LOCAL_API_INTEGRATION=true to use the local fixture API.',
  );

  test(
    'Buyer native authority reads and submits against the local API fixture',
    () async {
      final environment = Platform.environment;
      final identifier = environment['NEXA_DEV_BUYER_EMAIL'];
      final password = environment['NEXA_DEV_BUYER_PASSWORD'];
      final tenantSlug = environment['NEXA_DEV_TENANT_SLUG'];
      final workspaceSlug = environment['NEXA_DEV_WORKSPACE_SLUG'];
      expect(
        identifier,
        isNotNull,
        reason: 'Buyer fixture identifier is required.',
      );
      expect(
        password,
        isNotNull,
        reason: 'Buyer fixture password is required.',
      );
      expect(
        tenantSlug,
        isNotNull,
        reason: 'Tenant fixture scope is required.',
      );
      expect(
        workspaceSlug,
        isNotNull,
        reason: 'Workspace fixture scope is required.',
      );

      final origin = NexaApiOrigin.parse(_apiOrigin, allowLocalHttp: true);
      expect(
        {'localhost', '127.0.0.1', '::1'}.contains(origin.uri.host),
        isTrue,
        reason: 'This integration test must target the local Docker API.',
      );

      final transport = _NativeTransportAudit(http.Client());
      final api = NexaApiClient(origin: origin, httpClient: transport);
      final access = BuyerAccessRepositoryImpl(api);
      api.sessionCredentials = access;
      Directory? commandDirectory;

      try {
        await access.signIn(identifier: identifier!, password: password!);
        if (access.snapshot.status == BuyerAccessStatus.choosingContext) {
          final fixtureContexts = access.snapshot.availableContexts
              .where(
                (context) =>
                    context.roles.contains('BUYER') &&
                    context.tenantSlug == tenantSlug &&
                    context.workspaceSlug == workspaceSlug,
              )
              .toList(growable: false);
          expect(
            fixtureContexts,
            hasLength(1),
            reason: 'The fixture must resolve to one Buyer access context.',
          );
          await access.selectContext(fixtureContexts.single.membershipId);
        }

        final context = access.snapshot.currentContext;
        expect(access.snapshot.isSignedIn, isTrue);
        expect(context, isNotNull);
        expect(context!.roles, contains('BUYER'));
        expect(context.tenantSlug, tenantSlug);
        expect(context.workspaceSlug, workspaceSlug);
        final session = await api.get(
          '/session',
          refreshAfterUnauthorized: true,
        );
        expect(session.body['surface'], 'PORTAL');

        final purchaseRepository = PurchaseRequestRepositoryImpl(
          api,
          FilePurchaseRequestIdempotencyStore(
            directoryProvider: () async {
              commandDirectory ??= await Directory.systemTemp.createTemp(
                'nexa-buyer-api-test-',
              );
              return commandDirectory!;
            },
          ),
        );
        final buyerContext = await purchaseRepository
            .loadBuyerPurchaseContext();
        expect(buyerContext.clientAccountId, matches(_uuidPattern));
        expect(buyerContext.buyerMembershipId, context.membershipId);
        final existingDrafts = await purchaseRepository.listDrafts(page: 0);
        expect(existingDrafts.page, 0);
        expect(
          existingDrafts.totalItems,
          greaterThanOrEqualTo(existingDrafts.items.length),
        );

        final catalogRepository = CatalogRepositoryImpl(api);
        final catalog = await catalogRepository.list(query: '', page: 0);
        expect(catalog.items, isNotEmpty);
        final availableItems = catalog.items
            .where(
              (item) =>
                  item.sellableSkuId != null &&
                  (item.sellableAvailability ?? 0) >= 1,
            )
            .toList(growable: false);

        final credit = await BuyerCreditExposureRepositoryImpl(api)
            .readCurrentBuyerExposure(currency: 'PEN');
        expect(credit.clientAccountId, buyerContext.clientAccountId);
        expect(credit.currency, 'PEN');

        final ordersRepository = BuyerOrdersRepositoryImpl(api);
        final orders = await ordersRepository.list(page: 0);
        if (orders.items.isNotEmpty) {
          final order = await ordersRepository.detail(orders.items.first.id);
          expect(order.id, orders.items.first.id);
        }

        final documentsRepository = BusinessDocumentsRepositoryImpl(api);
        final documents = await documentsRepository.list(page: 0);
        final downloadable = documents.items.where(
          (document) =>
              document.contentAvailable &&
              document.byteSize <= NexaApiClient.maxProtectedBinaryBytes &&
              context.permissions.contains('document.read') &&
              context.permissions.contains('document.download'),
        );
        var verifiedBinary = false;
        for (final document in downloadable) {
          final content = await documentsRepository.download(document);
          expect(content.bytes, hasLength(document.byteSize));
          expect(content.sha256, document.checksumSha256!.toLowerCase());
          content.clear();
          verifiedBinary = true;
          break;
        }

        stdout.writeln('LOCAL_API_NATIVE_BUYER_CONTEXT=PASS');
        stdout.writeln('LOCAL_API_ACCOUNT_AND_CATALOG=PASS');
        stdout.writeln('LOCAL_API_SCOPED_DRAFT_LIST=PASS');
        stdout.writeln('LOCAL_API_CREDIT_READ=PASS');
        stdout.writeln(
          'LOCAL_API_ORDER_DETAILS=${orders.items.isEmpty ? 'NO_FIXTURE_ORDER' : 'PASS'}',
        );
        stdout.writeln('LOCAL_API_DOCUMENT_METADATA=PASS');
        stdout.writeln(
          'LOCAL_API_DOCUMENT_BINARY=${verifiedBinary ? 'PASS' : 'NO_AUTHORIZED_FIXTURE_DOCUMENT'}',
        );

        expect(
          availableItems,
          isNotEmpty,
          reason: 'The Buyer fixture needs an available sellable SKU.',
        );
        final sku = availableItems.first;
        final itemDetail = await catalogRepository.detail(sku.catalogItemId);
        expect(itemDetail.sellableSkuId, sku.sellableSkuId);
        expect(itemDetail.sellableSkuId, matches(_uuidPattern));
        final activeAddresses = buyerContext.addresses
            .where((address) => address.active)
            .toList(growable: false);
        expect(
          activeAddresses,
          isNotEmpty,
          reason: 'The Buyer fixture needs an active delivery address.',
        );
        final address = activeAddresses.firstWhere(
          (candidate) => candidate.defaultAddress,
          orElse: () => activeAddresses.first,
        );
        final requestedDeliveryDate = _nextWeekday(
          DateTime.now().add(const Duration(days: 14)),
        );

        var draft = await purchaseRepository.createDraft(
          clientAccountId: buyerContext.clientAccountId,
          requestedDeliveryDate: _dateOnly(requestedDeliveryDate),
        );
        draft = await purchaseRepository.replaceLine(
          draft: draft,
          sellableSkuId: itemDetail.sellableSkuId!,
          quantity: 1,
          unit: itemDetail.unitOfMeasure?.trim().isNotEmpty == true
              ? itemDetail.unitOfMeasure!.trim()
              : 'UNIT',
        );
        draft = await purchaseRepository.setDestination(
          draft: draft,
          addressId: address.id,
        );
        draft = await purchaseRepository.previewRoute(draft);
        draft = await purchaseRepository.setPreferences(
          draft: draft,
          paymentPreference: 'BANK_TRANSFER',
          requestedDeliveryDate: _dateOnly(requestedDeliveryDate),
        );
        final review = await purchaseRepository.review(draft.id);
        expect(
          review.readyToSubmit,
          isTrue,
          reason: review.readyToSubmit
              ? ''
              : 'The local Buyer fixture is not ready for request submission.',
        );
        final submitted = await purchaseRepository.submit(
          review.draft,
          scopeKey: context.scopeKey,
        );
        expect(submitted.status, 'SUBMITTED');
        final purchaseRequestDetail = await api.get(
          '/purchase-requests/${submitted.id}',
          refreshAfterUnauthorized: true,
        );
        expect(purchaseRequestDetail.body['status'], 'SUBMITTED');
        final submissionTrace = transport.requests.singleWhere(
          (request) => request.path.endsWith('/submissions'),
        );
        expect(submissionTrace.hasBearer, isTrue);
        expect(submissionTrace.hasIfMatch, isTrue);
        expect(submissionTrace.hasIdempotencyKey, isTrue);
        stdout.writeln('LOCAL_API_PURCHASE_REQUEST_SUBMIT_AND_DETAIL=PASS');
      } finally {
        await access.signOut();
        await access.dispose();
        api.close();
        if (commandDirectory != null && await commandDirectory!.exists()) {
          await commandDirectory!.delete(recursive: true);
        }
      }

      expect(transport.nativeHeadersApplied, isTrue);
      expect(transport.originHeaderAbsent, isTrue);
      expect(transport.mixedAuthoritiesAbsent, isTrue);
      expect(
        transport.requests.any(
          (request) => request.path.endsWith('/identity-sign-in'),
        ),
        isTrue,
      );
      expect(
        transport.requests.any(
          (request) => request.path.endsWith('/me/access-contexts'),
        ),
        transport.requests.any(
          (request) => request.path.endsWith('/me/access-context-selections'),
        ),
        reason: 'The native context picker must fetch and submit a server-issued access context together.',
      );
      stdout.writeln(
        'LOCAL_API_CONTEXT_RESOLUTION=${transport.requests.any((request) => request.path.endsWith('/me/access-contexts')) ? 'PICKER' : 'SESSION'}',
      );
      stdout.writeln('LOCAL_API_NATIVE_HTTP_POLICY=PASS');
    },
    skip: _runIntegration ? false : 'Set NEXA_RUN_LOCAL_API_INTEGRATION=true to use the local fixture API.',
  );
}

final _uuidPattern = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
);

DateTime _nextWeekday(DateTime value) {
  var result = DateTime(value.year, value.month, value.day);
  while (result.weekday == DateTime.saturday ||
      result.weekday == DateTime.sunday) {
    result = result.add(const Duration(days: 1));
  }
  return result;
}

String _dateOnly(DateTime value) =>
    '${value.year.toString().padLeft(4, '0')}-'
    '${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')}';

final class _NativeTransportAudit extends http.BaseClient {
  _NativeTransportAudit(this._delegate);

  final http.Client _delegate;
  final requests = <_RequestObservation>[];
  bool nativeHeadersApplied = true;
  bool originHeaderAbsent = true;
  bool mixedAuthoritiesAbsent = true;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) {
    final client = request.headers['X-Nexa-Client'];
    final surface = request.headers['X-Nexa-Surface'];
    final origin = request.headers['Origin'];
    final hasBearer = _hasValue(request.headers['Authorization']);
    final hasTicket = _hasValue(request.headers['X-Nexa-Context-Ticket']);
    final hasRefreshToken = _hasValue(request.headers['X-Nexa-Refresh-Token']);
    nativeHeadersApplied &= client == 'NATIVE' && surface == 'PORTAL';
    originHeaderAbsent &= origin == null;
    mixedAuthoritiesAbsent &=
        [
          hasBearer,
          hasTicket,
          hasRefreshToken,
        ].where((value) => value).length <=
        1;
    requests.add(
      _RequestObservation(
        method: request.method,
        path: request.url.path,
        hasBearer: hasBearer,
        hasContextTicket: hasTicket,
        hasIfMatch: _hasValue(request.headers['If-Match']),
        hasIdempotencyKey: _hasValue(request.headers['Idempotency-Key']),
      ),
    );
    return _delegate.send(request);
  }

  static bool _hasValue(String? value) => value != null && value.isNotEmpty;

  @override
  void close() => _delegate.close();
}

final class _RequestObservation {
  const _RequestObservation({
    required this.method,
    required this.path,
    required this.hasBearer,
    required this.hasContextTicket,
    required this.hasIfMatch,
    required this.hasIdempotencyKey,
  });

  final String method;
  final String path;
  final bool hasBearer;
  final bool hasContextTicket;
  final bool hasIfMatch;
  final bool hasIdempotencyKey;
}
