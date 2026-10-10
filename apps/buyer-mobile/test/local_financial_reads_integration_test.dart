import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:nexa_buyer_mobile/contexts/credit_receivables/infrastructure/buyer_credit_exposure_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/notifications/infrastructure/buyer_notifications_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/payments/infrastructure/buyer_payments_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/payments/infrastructure/buyer_wallet_repository_impl.dart';
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
    'reads Buyer credit, wallet, notifications, receivables, and payment history locally',
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
        expect(context!.permissions, contains('payment.read'));
        expect(context.permissions, contains('notification.read'));

        final creditRepository = BuyerCreditExposureRepositoryImpl(api);
        final exposure = await creditRepository.readCurrentBuyerExposure(
          currency: 'PEN',
        );
        final receivables = await creditRepository.listBuyerReceivables(
          page: 0,
        );
        expect(receivables.page, 0);
        expect(receivables.items.length, lessThanOrEqualTo(receivables.size));
        expect(
          receivables.total,
          greaterThanOrEqualTo(receivables.items.length),
        );
        expect(
          receivables.items.every(
            (receivable) =>
                receivable.clientAccountId == exposure.clientAccountId,
          ),
          isTrue,
        );

        final wallet = await BuyerWalletRepositoryImpl(api).readCurrentWallet(
          page: 0,
        );
        expect(wallet.currency, 'PEN');
        expect(wallet.movements.page, 0);

        final notifications = BuyerNotificationsRepositoryImpl(api);
        final inbox = await notifications.list(unreadOnly: false, limit: 25);
        expect(inbox.limit, 25);
        expect(inbox.items.length, lessThanOrEqualTo(25));
        final preferences = await notifications.preferences();
        expect(preferences.version, greaterThanOrEqualTo(0));
        expect(
          preferences.preferences.every(
            (preference) =>
                preference.channel == 'IN_APP' || preference.channel == 'EMAIL',
          ),
          isTrue,
        );

        var paymentHistoryCount = 0;
        final salesOrderId = environment['NEXA_LOCAL_SALES_ORDER_ID'];
        if (salesOrderId != null && salesOrderId.trim().isNotEmpty) {
          expect(
            RegExp(
              r'^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
              caseSensitive: false,
            ).hasMatch(salesOrderId),
            isTrue,
          );
          final linkedReceivables = <(int, Map<String, Object?>)>[];
          for (
            var pageIndex = 0;
            pageIndex * 25 < receivables.total;
            pageIndex++
          ) {
            final response = await api.get(
              '/receivables',
              query: {'page': '$pageIndex', 'size': '25'},
              refreshAfterUnauthorized: true,
            );
            final rawItems = response.body['items'];
            expect(rawItems, isA<List<Object?>>());
            for (final value in rawItems as List<Object?>) {
              if (value is Map<String, Object?> &&
                  value['subjectType'] == 'SALES_ORDER' &&
                  value['subjectId'] == salesOrderId) {
                linkedReceivables.add((pageIndex, value));
              }
            }
          }
          expect(linkedReceivables, hasLength(1));
          final (receivablePageIndex, linkedReceivable) =
              linkedReceivables.single;
          final receivableId = linkedReceivable['id'];
          expect(receivableId, isA<String>());

          final selectedPage = await creditRepository.listBuyerReceivables(
            page: receivablePageIndex,
          );
          final selected = selectedPage.items.singleWhere(
            (receivable) => receivable.id == receivableId,
          );
          expect(selected.clientAccountId, exposure.clientAccountId);
          expect(selected.status, 'OPEN');
          final detail = await api.get(
            '/receivables/$receivableId',
            refreshAfterUnauthorized: true,
          );
          expect(detail.body['subjectType'], 'SALES_ORDER');
          expect(detail.body['subjectId'], salesOrderId);
          expect(detail.body['clientAccountId'], exposure.clientAccountId);
          expect(detail.body['version'], selected.version);

          final history = await BuyerPaymentsRepositoryImpl(api)
              .listForReceivable(
                receivableId: selected.id,
                page: 0,
                expectedClientAccountId: exposure.clientAccountId,
              );
          expect(history.total, 0);
          paymentHistoryCount = history.total;
          stdout.writeln('LOCAL_API_BUYER_SALES_ORDER_LINK=PASS');
        } else if (receivables.items.isNotEmpty) {
          final selected = receivables.items.first;
          final history = await BuyerPaymentsRepositoryImpl(api)
              .listForReceivable(
                receivableId: selected.id,
                page: 0,
                expectedClientAccountId: exposure.clientAccountId,
              );
          expect(history.page, 0);
          expect(history.items.length, lessThanOrEqualTo(history.size));
          expect(history.total, greaterThanOrEqualTo(history.items.length));
          paymentHistoryCount = history.items.length;
        }
        stdout.writeln('LOCAL_API_BUYER_CREDIT_READ=PASS');
        stdout.writeln('LOCAL_API_BUYER_WALLET_READ=PASS');
        stdout.writeln('LOCAL_API_BUYER_NOTIFICATIONS_READ=PASS');
        stdout.writeln(
          'LOCAL_API_BUYER_RECEIVABLES=${receivables.items.length}',
        );
        stdout.writeln(
          'LOCAL_API_BUYER_PAYMENT_HISTORY=$paymentHistoryCount${receivables.items.isEmpty ? ' (NO_RECEIVABLE_FIXTURE)' : ''}',
        );
      } finally {
        await access.signOut();
        await access.dispose();
        api.close();
      }
    },
    skip: _runIntegration ? false : 'Set NEXA_RUN_LOCAL_API_INTEGRATION=true to use the local read-only fixture API.',
  );
}
