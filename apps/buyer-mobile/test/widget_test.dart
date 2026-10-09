import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:provider/provider.dart';
import 'package:nexa_buyer_mobile/contexts/business_documents/presentation/business_document_detail_page.dart';
import 'package:nexa_buyer_mobile/contexts/business_documents/presentation/business_documents_view_model.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';
import 'package:nexa_buyer_mobile/main.dart';

const _documentId = '00000000-0000-4000-8000-000000000001';
const _documentBytes = [37, 80, 68, 70, 45, 49, 46, 55];
const _deliveryId = '00000000-0000-4000-8000-000000000004';

void main() {
  testWidgets('shows safe configuration guidance without an API origin', (
    tester,
  ) async {
    await tester.pumpWidget(
      const BuyerMobileApp(apiBaseUrl: '', allowLocalHttp: false),
    );

    expect(find.text('Configura el origen de Nexa API'), findsOneWidget);
    expect(find.text('Nexa Buyer'), findsNothing);
  });

  testWidgets('hides delivery navigation without the exact tracking grant', (
    tester,
  ) async {
    final paths = <String>[];
    final httpClient = MockClient((request) async {
      final path = request.url.path;
      paths.add(path);
      if (path.endsWith('/authentication/identity-sign-in')) {
        return http.Response(
          jsonEncode({'outcome': 'CONTEXT_SELECTION_REQUIRED'}),
          200,
          headers: {'x-nexa-context-ticket': 'context-ticket'},
        );
      }
      if (path.endsWith('/me/access-contexts')) {
        return http.Response(
          jsonEncode({
            'accessContexts': [
              _contextOption(
                membershipId: 'membership-north',
                tenantId: 'tenant-north',
                tenantName: 'Empresa Norte',
                tenantSlug: 'empresa-norte',
                workspaceId: 'workspace-north',
                workspaceName: 'Sucursal principal',
                workspaceSlug: 'principal',
              ),
            ],
          }),
          200,
        );
      }
      if (path.endsWith('/me/access-context-selections')) {
        return http.Response(
          jsonEncode(
            _authenticationResponse(
              'membership-north',
              'tenant-north',
              includeTracking: false,
            ),
          ),
          200,
          headers: {'x-nexa-refresh-token': 'refresh-secret'},
        );
      }
      if (path == '/api/v1/catalog-items') {
        return http.Response(
          jsonEncode({
            'items': <Object?>[],
            'page': 0,
            'size': 20,
            'totalItems': 0,
            'totalPages': 0,
          }),
          200,
        );
      }
      fail('Unexpected API request ${request.method} ${request.url}');
    });

    await tester.pumpWidget(
      BuyerMobileApp(
        apiBaseUrl: 'https://api.nexa.example',
        allowLocalHttp: false,
        httpClient: httpClient,
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextFormField).at(0),
      'buyer@example.test',
    );
    await tester.enterText(find.byType(TextFormField).at(1), 'test-password');
    await tester.tap(find.text('Continuar'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Empresa Norte'));
    await tester.pumpAndSettle();

    expect(find.text('Catálogo'), findsOneWidget);
    expect(find.text('Mis entregas'), findsNothing);
    expect(paths, isNot(contains('/api/v1/buyer/deliveries')));
  });

  testWidgets(
    'signs in, selects a Buyer context, and opens catalog and order details',
    (tester) async {
      final requests = <http.Request>[];
      var documentDownloads = 0;
      final documentChecksum = sha256Of(_documentBytes);
      final httpClient = MockClient((request) async {
        requests.add(request);
        final headers = _normalizedHeaders(request);
        final path = request.url.path;

        if (path.endsWith('/authentication/identity-sign-in')) {
          expect(headers['x-nexa-client'], 'NATIVE');
          expect(headers['x-nexa-surface'], 'PORTAL');
          expect(headers.containsKey('authorization'), isFalse);
          expect(jsonDecode(request.body), {
            'identifier': 'buyer@example.test',
            'password': 'test-password',
            'surface': 'PORTAL',
          });
          return http.Response(
            jsonEncode({'outcome': 'CONTEXT_SELECTION_REQUIRED'}),
            200,
            headers: {'x-nexa-context-ticket': 'context-ticket'},
          );
        }
        if (path.endsWith('/me/access-contexts')) {
          expect(headers['x-nexa-context-ticket'], 'context-ticket');
          expect(headers.containsKey('authorization'), isFalse);
          return http.Response(
            jsonEncode({
              'accessContexts': [
                _contextOption(
                  membershipId: 'membership-north',
                  tenantId: 'tenant-north',
                  tenantName: 'Empresa Norte',
                  tenantSlug: 'empresa-norte',
                  workspaceId: 'workspace-north',
                  workspaceName: 'Sucursal principal',
                  workspaceSlug: 'principal',
                ),
                _contextOption(
                  membershipId: 'membership-south',
                  tenantId: 'tenant-south',
                  tenantName: 'Empresa Sur',
                  tenantSlug: 'empresa-sur',
                  workspaceId: 'workspace-south',
                  workspaceName: 'Sucursal sur',
                  workspaceSlug: 'sur',
                ),
              ],
            }),
            200,
          );
        }
        if (path.endsWith('/me/access-context-selections')) {
          expect(headers['x-nexa-context-ticket'], 'context-ticket');
          expect(headers.containsKey('authorization'), isFalse);
          expect(jsonDecode(request.body), {
            'membershipId': 'membership-north',
          });
          return http.Response(
            jsonEncode(
              _authenticationResponse('membership-north', 'tenant-north'),
            ),
            200,
            headers: {'x-nexa-refresh-token': 'refresh-secret'},
          );
        }
        if (path == '/api/v1/catalog-items') {
          expect(headers['authorization'], 'Bearer access-secret');
          expect(
            request.url.queryParameters.containsKey('clientAccountId'),
            isFalse,
          );
          return http.Response(
            jsonEncode({
              'items': [
                {
                  'catalogItemId': 'CAT-0001',
                  'itemName': 'Café de altura',
                  'brandName': 'Valle',
                  'presentation': 'Bolsa de 250 g',
                  'skuCode': 'CAF-250',
                  'unitOfMeasure': 'UN',
                  'availabilityStatus': 'AVAILABLE',
                  'currentOfferPrice': {'amount': '12.50', 'currency': 'PEN'},
                },
              ],
              'page': 0,
              'size': 20,
              'totalItems': 1,
              'totalPages': 1,
            }),
            200,
          );
        }
        if (path == '/api/v1/catalog-items/CAT-0001') {
          expect(headers['authorization'], 'Bearer access-secret');
          return http.Response(
            jsonEncode({
              'catalogItemId': 'CAT-0001',
              'itemName': 'Café de altura',
              'brandName': 'Valle',
              'categoryName': 'Bebidas',
              'description': 'Café tostado en pequeños lotes.',
              'presentation': 'Bolsa de 250 g',
              'status': 'ACTIVE',
              'availabilityStatus': 'AVAILABLE',
              'coldChainRequirement': 'NONE',
              'skuCode': 'CAF-250',
              'unitOfMeasure': 'UN',
              'sellableAvailability': 18,
              'currentOfferPrice': {'amount': '12.50', 'currency': 'PEN'},
              'pricingAsOf': '2026-10-09T12:00:00Z',
              'availabilityAsOf': '2026-10-09T12:00:00Z',
            }),
            200,
          );
        }
        if (path == '/api/v1/sales-orders') {
          expect(headers['authorization'], 'Bearer access-secret');
          expect(
            request.url.queryParameters.containsKey('clientAccountId'),
            isFalse,
          );
          return http.Response(
            jsonEncode({
              'items': [_order()],
              'page': 0,
              'size': 25,
              'total': 1,
            }),
            200,
          );
        }
        if (path == '/api/v1/buyer/deliveries') {
          expect(headers['authorization'], 'Bearer access-secret');
          expect(request.url.queryParameters, {'page': '0', 'size': '25'});
          expect(
            request.url.queryParameters.containsKey('clientAccountId'),
            isFalse,
          );
          return http.Response(
            jsonEncode({
              'items': [_delivery()],
              'page': 0,
              'size': 25,
              'total': 1,
            }),
            200,
          );
        }
        if (path == '/api/v1/buyer/deliveries/$_deliveryId') {
          return http.Response(jsonEncode(_delivery()), 200);
        }
        if (path == '/api/v1/buyer/deliveries/$_deliveryId/events') {
          return http.Response(
            jsonEncode([
              {
                'type': 'HANDED_OVER',
                'occurredAt': '2026-10-09T12:00:00Z',
                'actorMembershipId': 'PRIVATE ACTOR',
                'reason': 'PRIVATE INTERNAL REASON',
                'assignedDriver': 'PRIVATE ASSIGNMENT',
                'fromStatus': 'PRIVATE STATUS DETAIL',
              },
            ]),
            200,
          );
        }
        if (path.endsWith(
          '/sales-orders/00000000-0000-0000-0000-000000000001',
        )) {
          expect(headers['authorization'], 'Bearer access-secret');
          return http.Response(jsonEncode(_order()), 200);
        }
        if (path == '/api/v1/client-accounts/me/credit-exposure') {
          expect(headers['authorization'], 'Bearer access-secret');
          expect(request.url.queryParameters, {'currency': 'PEN'});
          expect(request.body, isEmpty);
          expect(
            request.url.queryParameters.containsKey('clientAccountId'),
            isFalse,
          );
          return http.Response(
            jsonEncode({
              'clientAccountId': '00000000-0000-4000-8000-000000000002',
              'currency': 'PEN',
              'creditLimit': 500,
              'ledgerExposure': 140,
              'outstandingReceivables': 80,
              'reservedExposure': 60,
              'used': 240,
              'availableCredit': 260,
              'active': true,
              'asOf': '2026-10-09T12:00:00Z',
            }),
            200,
          );
        }
        if (path == '/api/v1/receivables') {
          expect(request.method, 'GET');
          expect(request.url.queryParameters, {'page': '0', 'size': '25'});
          expect(
            request.url.queryParameters.containsKey('clientAccountId'),
            isFalse,
          );
          return http.Response(
            jsonEncode({
              'items': [
                {
                  'id': '00000000-0000-4000-8000-000000000010',
                  'clientAccountId': '00000000-0000-4000-8000-000000000002',
                  'subjectType': 'SALES_ORDER',
                  'subjectId': '00000000-0000-4000-8000-000000000001',
                  'number': 'REC-0001',
                  'currency': 'PEN',
                  'amount': 80,
                  'amountPaid': 0,
                  'remaining': 80,
                  'status': 'OPEN',
                  'dueAt': null,
                  'version': 1,
                },
              ],
              'page': 0,
              'size': 25,
              'total': 1,
            }),
            200,
          );
        }
        if (path ==
            '/api/v1/receivables/00000000-0000-4000-8000-000000000010/payments') {
          expect(request.method, 'GET');
          expect(request.url.queryParameters, {'page': '0', 'size': '25'});
          return http.Response(
            jsonEncode({
              'items': <Object?>[],
              'page': 0,
              'size': 25,
              'total': 0,
            }),
            200,
          );
        }
        if (path == '/api/v1/business-documents') {
          expect(request.url.queryParameters, {'page': '0', 'size': '25'});
          expect(
            request.url.queryParameters.containsKey('clientAccountId'),
            isFalse,
          );
          return http.Response(
            jsonEncode({
              'items': [_businessDocument(documentChecksum)],
              'page': 0,
              'size': 25,
              'total': 1,
            }),
            200,
          );
        }
        if (path == '/api/v1/business-documents/$_documentId') {
          return http.Response(
            jsonEncode(_businessDocument(documentChecksum)),
            200,
          );
        }
        if (path == '/api/v1/business-documents/$_documentId/downloads') {
          documentDownloads++;
          expect(headers['authorization'], 'Bearer access-secret');
          expect(headers['x-nexa-client'], 'NATIVE');
          expect(headers['x-nexa-surface'], 'PORTAL');
          return http.Response.bytes(
            _documentBytes,
            200,
            headers: {
              'content-type': 'application/pdf',
              'x-content-sha256': documentChecksum,
              'cache-control': 'private, no-store',
            },
          );
        }
        fail('Unexpected API request ${request.method} ${request.url}');
      });

      await tester.pumpWidget(
        BuyerMobileApp(
          apiBaseUrl: 'https://api.nexa.example',
          allowLocalHttp: false,
          httpClient: httpClient,
        ),
      );
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byType(TextFormField).at(0),
        'buyer@example.test',
      );
      await tester.enterText(find.byType(TextFormField).at(1), 'test-password');
      await tester.tap(find.text('Continuar'));
      await tester.pumpAndSettle();
      expect(find.text('Elige tu empresa'), findsOneWidget);
      expect(find.text('Empresa Norte'), findsOneWidget);
      expect(find.text('Empresa Sur'), findsOneWidget);

      await tester.tap(find.text('Empresa Norte'));
      await tester.pumpAndSettle();
      expect(find.text('Café de altura'), findsOneWidget);
      expect(find.text('Precio 12.50 PEN'), findsOneWidget);

      await tester.tap(find.text('Café de altura'));
      await tester.pumpAndSettle();
      expect(find.text('Café tostado en pequeños lotes.'), findsOneWidget);
      expect(find.textContaining('18 UN'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();

      await tester.tap(find.text('Mis pedidos'));
      await tester.pumpAndSettle();
      expect(find.text('Pedido SO-0001'), findsOneWidget);
      await tester.tap(find.text('Pedido SO-0001'));
      await tester.pumpAndSettle();
      expect(find.text('Productos'), findsOneWidget);
      expect(find.text('Café de altura'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();

      await tester.tap(find.text('Crédito y pagos'));
      await tester.pumpAndSettle();
      expect(find.text('260 PEN'), findsOneWidget);
      expect(find.text('Exposición usada'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('REC-0001'), 400);
      expect(find.text('REC-0001'), findsOneWidget);
      await tester.tap(find.text('REC-0001'));
      await tester.pumpAndSettle();
      expect(find.text('Cuenta REC-0001'), findsOneWidget);
      const paymentHistoryTitle = Key('buyer-payment-history-title');
      await tester.drag(
        find.byKey(const Key('buyer-payment-activity-list')),
        const Offset(0, -500),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(paymentHistoryTitle), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();

      await tester.tap(find.text('Documentos'));
      await tester.pumpAndSettle();
      expect(find.text('INV-2026-01'), findsOneWidget);
      expect(documentDownloads, 0);
      await tester.tap(find.text('INV-2026-01'));
      await tester.pumpAndSettle();
      expect(find.text('INVOICE'), findsOneWidget);
      expect(find.text('GENERATED'), findsOneWidget);
      expect(documentDownloads, 0);
      await tester.ensureVisible(find.text('Cargar contenido protegido'));
      final detailViewModel = tester
          .element(find.byType(BusinessDocumentDetailPage))
          .read<BusinessDocumentDetailViewModel>();
      await tester.tap(find.text('Cargar contenido protegido'));
      await tester.pump();
      await tester.runAsync(() async {
        for (
          var attempt = 0;
          attempt < 40 &&
              detailViewModel.downloadStatus ==
                  BusinessDocumentDownloadStatus.loading;
          attempt++
        ) {
          await Future<void>.delayed(const Duration(milliseconds: 25));
        }
      });
      await tester.pump();
      expect(
        detailViewModel.downloadStatus,
        BusinessDocumentDownloadStatus.current,
        reason:
            'The protected binary response should finish within one second.',
      );
      expect(find.text('Contenido verificado'), findsOneWidget);
      expect(
        find.textContaining('No se guardó en el dispositivo.'),
        findsOneWidget,
      );
      expect(documentDownloads, 1);

      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.tap(find.text('Mis entregas'));
      await tester.pumpAndSettle();
      expect(find.text('Pedido SO-0001'), findsOneWidget);
      expect(find.text('Estado: Dispatched'), findsOneWidget);
      await tester.tap(find.text('Pedido SO-0001'));
      await tester.pumpAndSettle();
      expect(find.text('Historial de entrega'), findsOneWidget);
      expect(find.text('Handed Over'), findsOneWidget);
      expect(find.textContaining('PRIVATE ASSIGNMENT'), findsNothing);
      expect(find.textContaining('PRIVATE STATUS DETAIL'), findsNothing);
      expect(find.textContaining('PRIVATE ACTOR'), findsNothing);
      expect(find.textContaining('PRIVATE INTERNAL REASON'), findsNothing);

      expect(
        requests.map((request) => request.url.path),
        containsAll([
          '/api/v1/authentication/identity-sign-in',
          '/api/v1/me/access-contexts',
          '/api/v1/me/access-context-selections',
          '/api/v1/catalog-items',
          '/api/v1/catalog-items/CAT-0001',
          '/api/v1/sales-orders',
          '/api/v1/sales-orders/00000000-0000-0000-0000-000000000001',
          '/api/v1/buyer/deliveries',
          '/api/v1/buyer/deliveries/$_deliveryId',
          '/api/v1/buyer/deliveries/$_deliveryId/events',
          '/api/v1/client-accounts/me/credit-exposure',
          '/api/v1/business-documents',
          '/api/v1/business-documents/$_documentId',
          '/api/v1/business-documents/$_documentId/downloads',
        ]),
      );
    },
  );
}

Map<String, Object?> _contextOption({
  required String membershipId,
  required String tenantId,
  required String tenantName,
  required String tenantSlug,
  required String workspaceId,
  required String workspaceName,
  required String workspaceSlug,
}) => {
  'membershipId': membershipId,
  'tenantId': tenantId,
  'tenantName': tenantName,
  'tenantSlug': tenantSlug,
  'workspaceId': workspaceId,
  'workspaceName': workspaceName,
  'workspaceSlug': workspaceSlug,
};

Map<String, Object?> _authenticationResponse(
  String membershipId,
  String tenantId, {
  bool includeTracking = true,
}) => {
  'accessToken': 'access-secret',
  'tokenType': 'Bearer',
  'expiresIn': 900,
  'session': {
    'userId': 'user-1',
    'displayName': 'Buyer One',
    'email': 'buyer@example.test',
    'tenantId': tenantId,
    'tenantSlug': 'empresa-norte',
    'workspaceId': 'workspace-north',
    'workspaceSlug': 'principal',
    'membershipId': membershipId,
    'roles': ['BUYER'],
    'permissions': [
      'catalog.read',
      'buyer.order.read',
      'document.read',
      'document.download',
      'payment.read',
      if (includeTracking) 'buyer.tracking.read',
    ],
    'authorizationVersion': 7,
    'surface': 'PORTAL',
  },
};

Map<String, Object?> _order() => {
  'id': '00000000-0000-0000-0000-000000000001',
  'number': 'SO-0001',
  'status': 'CONFIRMED',
  'version': 1,
  'createdAt': '2026-10-09T12:00:00Z',
  'requestedDeliveryDate': '2026-10-12',
  'total': 25,
  'currency': 'PEN',
  'lines': [
    {
      'itemName': 'Café de altura',
      'quantity': 2,
      'unit': 'UN',
      'presentation': 'Bolsa de 250 g',
      'unitPriceAmount': 12.50,
      'unitPriceCurrency': 'PEN',
      'lineSubtotal': 25,
    },
  ],
};

Map<String, Object?> _delivery() => {
  'id': _deliveryId,
  'salesOrderNumber': 'SO-0001',
  'status': 'DISPATCHED',
  'destination': 'Sucursal principal',
  'scheduledAt': null,
  'dispatchedAt': '2026-10-09T12:00:00Z',
  'deliveredAt': null,
  'proofOfDeliveryStatus': null,
  'version': 3,
  'createdAt': '2026-10-09T11:00:00Z',
  'updatedAt': '2026-10-09T12:05:00Z',
  'assignedDriver': 'PRIVATE ASSIGNMENT',
  'driverName': 'PRIVATE DRIVER',
  'vehiclePlate': 'PRIVATE VEHICLE',
};

Map<String, Object?> _businessDocument(String checksum) => {
  'id': _documentId,
  'clientAccountId': '00000000-0000-4000-8000-000000000002',
  'subjectType': 'SALES_ORDER',
  'subjectId': '00000000-0000-4000-8000-000000000003',
  'documentType': 'INVOICE',
  'documentNumber': 'INV-2026-01',
  'version': 1,
  'status': 'GENERATED',
  'format': 'PDF',
  'contentType': 'application/pdf',
  'checksumSha256': checksum,
  'byteSize': _documentBytes.length,
  'generatedAt': '2026-10-09T12:00:00Z',
  'createdAt': '2026-10-09T12:00:00Z',
  'updatedAt': '2026-10-09T12:00:00Z',
};

Map<String, String> _normalizedHeaders(http.BaseRequest request) => {
  for (final entry in request.headers.entries)
    entry.key.toLowerCase(): entry.value,
};
