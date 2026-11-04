import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nexa_buyer_mobile/app/buyer_catalog_highlights.dart';
import 'package:nexa_buyer_mobile/app/buyer_catalog_preview.dart';
import 'package:nexa_buyer_mobile/app/buyer_home_widgets.dart';
import 'package:nexa_buyer_mobile/app/buyer_shopping_guide.dart';
import 'package:nexa_buyer_mobile/contexts/catalog_commercial_policy/application/catalog_repository.dart';
import 'package:nexa_buyer_mobile/contexts/credit_receivables/application/credit_exposure_repository.dart';
import 'package:nexa_buyer_mobile/core/presentation/nexa_action_surface.dart';
import 'package:nexa_buyer_mobile/core/presentation/nexa_buyer_palette.dart';
import 'package:nexa_buyer_mobile/core/presentation/nexa_buyer_theme.dart';

void main() {
  testWidgets('catalog photo exposes a button and selects its projection', (
    tester,
  ) async {
    final item = CatalogItemProjection(
      catalogItemId: 'catalog-item-7',
      itemName: 'Pan integral',
      availabilityStatus: 'AVAILABLE',
      imageFileName: 'pan-integral.png',
    );
    CatalogItemProjection? selected;

    await tester.pumpWidget(
      MaterialApp(
        theme: NexaBuyerTheme.light,
        home: Scaffold(
          body: BuyerCatalogPreview(
            items: [item],
            onOpen: () {},
            onSelected: (value) => selected = value,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final semantics = tester.ensureSemantics();
    expect(find.bySemanticsLabel('Ver Pan integral'), findsOneWidget);

    await tester.tap(find.bySemanticsLabel('Ver Pan integral'));
    expect(selected, same(item));
    semantics.dispose();
  });

  testWidgets('category label keeps the exact server projection on select', (
    tester,
  ) async {
    const category = CatalogFilterOptionProjection(
      id: 'category-17',
      name: 'charcuterie',
    );
    CatalogFilterOptionProjection? selected;

    await tester.pumpWidget(
      MaterialApp(
        theme: NexaBuyerTheme.light,
        home: Scaffold(
          body: BuyerCategoryShortcuts(
            items: const [category],
            onSelected: (value) => selected = value,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Charcutería'), findsOneWidget);
    await tester.tap(find.text('Charcutería'));

    expect(selected, same(category));
    expect(selected!.name, 'charcuterie');
  });

  testWidgets('all orders and latest order invoke distinct callbacks', (
    tester,
  ) async {
    var allOrdersOpens = 0;
    var latestOrderOpens = 0;

    await tester.pumpWidget(
      MaterialApp(
        theme: NexaBuyerTheme.light,
        home: Scaffold(
          body: BuyerOrdersWidget(
            total: 1,
            code: 'SO-2026-000020',
            status: 'IN_PROGRESS',
            createdAt: '2026-10-24T09:00:00Z',
            amount: 1250,
            currency: 'PEN',
            message: 'No tienes pedidos activos.',
            onOpen: () => allOrdersOpens++,
            onLatest: () => latestOrderOpens++,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Ver todos'));
    expect(allOrdersOpens, 1);
    expect(latestOrderOpens, 0);

    await tester.tap(find.text('SO-2026-000020'));
    expect(allOrdersOpens, 1);
    expect(latestOrderOpens, 1);
  });

  testWidgets('home cards retain last known data while refreshing', (
    tester,
  ) async {
    const exposure = BuyerCreditExposureProjection(
      clientAccountId: 'account-1',
      currency: 'PEN',
      creditLimit: '256',
      ledgerExposure: '64',
      outstandingReceivables: '64',
      reservedExposure: '0',
      used: '128',
      availableCredit: '128',
      active: true,
      asOf: null,
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: NexaBuyerTheme.light,
        home: Scaffold(
          body: SingleChildScrollView(
            child: Column(
              children: [
                BuyerOrdersWidget(
                  total: 19,
                  code: 'SO-2026-000020',
                  status: 'IN_PROGRESS',
                  createdAt: '2026-10-24T09:00:00Z',
                  amount: 390,
                  currency: 'PEN',
                  refreshing: true,
                  message: 'Consultando pedidos…',
                  onOpen: () {},
                  onLatest: () {},
                ),
                BuyerCreditWidget(
                  exposure: exposure,
                  refreshing: true,
                  message: 'Consultando tu crédito…',
                  onOpen: () {},
                ),
                BuyerRequestWidget(
                  code: 'PR-8F214F3A',
                  status: 'CONVERTED_TO_ORDER',
                  productCount: 1,
                  refreshing: true,
                  message: 'Consultando solicitudes…',
                  onOpen: () {},
                ),
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('19 activos · Actualizando…'), findsOneWidget);
    expect(find.text('SO-2026-000020'), findsOneWidget);
    expect(find.text('S/ 390'), findsOneWidget);
    expect(find.text('Crédito · Actualizando…'), findsOneWidget);
    expect(find.text('S/ 128'), findsOneWidget);
    expect(find.text('Límite S/ 256'), findsOneWidget);
    expect(find.text('Solicitud · Actualizando…'), findsOneWidget);
    expect(find.text('PR-8F214F3A'), findsOneWidget);
    expect(find.text('Convertida en pedido'), findsOneWidget);
  });

  testWidgets('home presentation has no overflow at 320 dp and 150% text', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 960);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final products = [
      for (var index = 1; index <= 3; index++)
        CatalogItemProjection(
          catalogItemId: 'catalog-item-$index',
          itemName: 'Queso artesanal madurado $index',
          availabilityStatus: 'AVAILABLE',
        ),
    ];
    final categories = [
      const CatalogFilterOptionProjection(id: 'cat-1', name: 'charcuterie'),
      const CatalogFilterOptionProjection(id: 'cat-2', name: 'cheese'),
      const CatalogFilterOptionProjection(id: 'cat-3', name: 'dessert'),
    ];
    final catalogPreview = BuyerCatalogPreview(
      items: products,
      total: 50,
      onOpen: () {},
      onSelected: (_) {},
    );
    final categoryShortcuts = BuyerCategoryShortcuts(
      items: categories,
      onSelected: (_) {},
    );
    final orders = BuyerOrdersWidget(
      total: 19,
      code: 'SO-2026-000020',
      status: 'IN_PROGRESS',
      createdAt: '2026-10-24T09:00:00Z',
      amount: 1250,
      currency: 'PEN',
      message: 'No tienes pedidos activos.',
      onOpen: () {},
      onLatest: () {},
    );
    final credit = BuyerCreditWidget(
      message: 'Consultando tu crédito…',
      onOpen: () {},
    );
    final request = BuyerRequestWidget(
      code: 'PR-8F214F3A',
      status: 'CONVERTED_TO_ORDER',
      productCount: 1,
      message: 'Sin solicitudes recientes.',
      onOpen: () {},
    );
    final guide = const BuyerShoppingGuide();
    final highlights = BuyerCatalogHighlights(
      items: products,
      onSelected: (_) {},
    );

    Widget scaledApp(Widget child) => MaterialApp(
      theme: NexaBuyerTheme.light,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(1.5)),
        child: child!,
      ),
      home: Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: child,
        ),
      ),
    );

    for (final entry in <MapEntry<String, Widget>>[
      MapEntry('catalog preview', catalogPreview),
      MapEntry('category shortcuts', categoryShortcuts),
      MapEntry('orders card', orders),
      MapEntry('credit card', credit),
      MapEntry('request card', request),
      MapEntry('shopping guide', guide),
      MapEntry('catalog highlights', highlights),
      MapEntry(
        'secondary cards',
        BuyerSecondaryWidgets(children: [credit, request]),
      ),
    ]) {
      await tester.pumpWidget(scaledApp(entry.value));
      await tester.pumpAndSettle();
      final exception = tester.takeException();
      expect(
        exception,
        isNull,
        reason: '${entry.key} should fit at 320 dp and 150% text',
      );
    }

    await tester.pumpWidget(
      scaledApp(
        Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            catalogPreview,
            const SizedBox(height: 12),
            categoryShortcuts,
            const SizedBox(height: 12),
            orders,
            const SizedBox(height: 12),
            BuyerSecondaryWidgets(children: [credit, request]),
            const SizedBox(height: 12),
            guide,
            const SizedBox(height: 12),
            highlights,
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(find.text('Tus pedidos'), findsOneWidget);
    expect(find.text('Última solicitud'), findsOneWidget);

    await tester.ensureVisible(find.text('Cómo hacer tu pedido'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cómo hacer tu pedido'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('NexaActionSurface shows its focus border after Tab', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: NexaBuyerTheme.light,
        home: Scaffold(
          body: Center(
            child: NexaActionSurface(
              onTap: () {},
              child: const Padding(
                padding: EdgeInsets.all(16),
                child: Text('Abrir catálogo'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final material = find.descendant(
      of: find.byType(NexaActionSurface),
      matching: find.byType(Material),
    );
    expect(material, findsOneWidget);
    var shape =
        tester.widget<Material>(material).shape! as RoundedRectangleBorder;
    expect(shape.side.width, 0);

    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pumpAndSettle();

    shape = tester.widget<Material>(material).shape! as RoundedRectangleBorder;
    expect(shape.side.width, 2);
    expect(shape.side.color, NexaBuyerPalette.primary);
  });

  test('Buyer text and primary color retain WCAG AA contrast', () {
    const lightSurfaces = [
      NexaBuyerPalette.surface,
      NexaBuyerPalette.cardSurface,
      NexaBuyerPalette.primarySurface,
      NexaBuyerPalette.positiveSurface,
      NexaBuyerPalette.attentionSurface,
      NexaBuyerPalette.coldSurface,
      NexaBuyerPalette.frozenSurface,
    ];

    for (final surface in lightSurfaces) {
      expect(
        _contrastRatio(NexaBuyerPalette.ink, surface),
        greaterThanOrEqualTo(4.5),
        reason: 'ink text on $surface',
      );
      expect(
        _contrastRatio(NexaBuyerPalette.muted, surface),
        greaterThanOrEqualTo(4.5),
        reason: 'muted text on $surface',
      );
    }
    expect(
      _contrastRatio(NexaBuyerPalette.primary, Colors.white),
      greaterThanOrEqualTo(4.5),
    );
  });
}

double _contrastRatio(Color first, Color second) {
  final firstLuminance = first.computeLuminance();
  final secondLuminance = second.computeLuminance();
  final lighter = firstLuminance > secondLuminance
      ? firstLuminance
      : secondLuminance;
  final darker = firstLuminance > secondLuminance
      ? secondLuminance
      : firstLuminance;
  return (lighter + 0.05) / (darker + 0.05);
}
