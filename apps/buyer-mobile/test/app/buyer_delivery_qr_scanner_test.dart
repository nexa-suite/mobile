import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:nexa_buyer_mobile/app/buyer_delivery_qr_scanner.dart';

void main() {
  late MobileScannerPlatform previous;
  setUp(() {
    TestWidgetsFlutterBinding.ensureInitialized()
        .handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    previous = MobileScannerPlatform.instance;
  });
  tearDown(() {
    MobileScannerPlatform.instance = previous;
  });

  testWidgets(
    'denied camera offers manual entry and returns trimmed code once',
    (tester) async {
      MobileScannerPlatform.instance = _Camera(denied: true);
      final results = <String?>[];
      await _open(tester, results);
      expect(find.textContaining('No podemos usar la cámara'), findsOneWidget);
      await tester.tap(find.text('Escribir código'));
      await tester.pumpAndSettle();
      expect(find.byType(TextField), findsOneWidget);
      await tester.enterText(find.byType(TextField), '  entrega-token_123  ');
      await tester.tap(find.text('Continuar'));
      await tester.tap(find.text('Continuar'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(results, ['entrega-token_123']);
    },
  );

  testWidgets(
    'manual entry is available with a working camera and cancel returns null',
    (tester) async {
      MobileScannerPlatform.instance = _Camera();
      final results = <String?>[];
      await _open(tester, results);
      await tester.tap(find.text('Escribir código'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'unused');
      await tester.tap(find.text('Volver sin confirmar'));
      await tester.pumpAndSettle();
      expect(results, [null]);
    },
  );

  testWidgets('empty and whitespace-containing codes stay in manual mode', (
    tester,
  ) async {
    MobileScannerPlatform.instance = _Camera(denied: true);
    final results = <String?>[];
    await _open(tester, results);
    await tester.tap(find.text('Escribir código'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continuar'));
    await tester.pump();
    expect(find.text('Usa el código de entrega sin espacios.'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'abc def');
    await tester.tap(find.text('Continuar'));
    await tester.pump();
    expect(results, isEmpty);
    expect(find.byType(TextField), findsOneWidget);
    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.maxLength, 400);
    expect(field.autocorrect, isFalse);
    expect(field.enableSuggestions, isFalse);
  });

  testWidgets('manual submission cannot return while app is paused', (
    tester,
  ) async {
    MobileScannerPlatform.instance = _Camera(denied: true);
    final results = <String?>[];
    await _open(tester, results);
    await tester.tap(find.text('Escribir código'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'valid-token');
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.tap(find.text('Continuar'));
    await tester.pump();
    expect(results, isEmpty);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.tap(find.text('Continuar'));
    await tester.pumpAndSettle();
    expect(results, ['valid-token']);
  });

  testWidgets('late camera detection cannot return after manual mode opens', (
    tester,
  ) async {
    final camera = _Camera();
    MobileScannerPlatform.instance = camera;
    final results = <String?>[];
    await _open(tester, results);
    final detector = tester
        .widget<MobileScanner>(find.byType(MobileScanner))
        .onDetect!;
    await tester.tap(find.text('Escribir código'));
    await tester.pumpAndSettle();
    detector(
      const BarcodeCapture(
        barcodes: [Barcode(format: BarcodeFormat.qrCode, rawValue: 'old')],
      ),
    );
    await tester.pump();
    expect(results, isEmpty);
    expect(find.byType(TextField), findsOneWidget);
  });
}

Future<void> _open(WidgetTester tester, List<String?> results) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async {
            results.add(
              await Navigator.of(context).push<String>(
                MaterialPageRoute(
                  builder: (_) => const BuyerDeliveryQrScanner(),
                ),
              ),
            );
          },
          child: const Text('open'),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

final class _Camera extends MobileScannerPlatform {
  _Camera({this.denied = false});
  final bool denied;
  final stream = StreamController<BarcodeCapture>.broadcast();
  @override
  Stream<BarcodeCapture?> get barcodesStream => stream.stream;
  @override
  Stream<TorchState> get torchStateStream => const Stream.empty();
  @override
  Stream<double> get zoomScaleStateStream => const Stream.empty();
  @override
  Future<MobileScannerViewAttributes> start(StartOptions options) async {
    if (denied) {
      throw const MobileScannerException(
        errorCode: MobileScannerErrorCode.permissionDenied,
      );
    }
    return const MobileScannerViewAttributes(
      cameraDirection: CameraFacing.back,
      currentTorchMode: TorchState.unavailable,
      size: Size(200, 200),
      numberOfCameras: 1,
    );
  }

  @override
  Widget buildCameraView() => const SizedBox();
  @override
  Future<void> stop() async {}
  @override
  Future<void> dispose() async {
    await stream.close();
  }
}
