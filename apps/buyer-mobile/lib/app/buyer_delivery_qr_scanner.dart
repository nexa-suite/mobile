import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// The camera only reads a QR. Acceptance belongs to the receipt workflow.
final class BuyerDeliveryQrScanner extends StatefulWidget {
  const BuyerDeliveryQrScanner({super.key});
  @override
  State<BuyerDeliveryQrScanner> createState() => _BuyerDeliveryQrScannerState();
}

final class _BuyerDeliveryQrScannerState extends State<BuyerDeliveryQrScanner> {
  bool _returned = false;
  String? _notice;

  void _detected(BarcodeCapture capture) {
    if (!mounted ||
        _returned ||
        WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed) {
      return;
    }
    for (final barcode in capture.barcodes) {
      final value = barcode.rawValue?.trim();
      if (barcode.format != BarcodeFormat.qrCode || value == null) continue;
      if (value.isEmpty ||
          value.length > 400 ||
          value.contains(RegExp(r'\s'))) {
        setState(
          () => _notice = 'Usa el QR de entrega que te muestra el repartidor.',
        );
        return;
      }
      _returned = true;
      Navigator.of(context).pop(value);
      return;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Recibir mi pedido')),
    body: SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          child: Column(
            children: [
              const Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'Al escanear el QR del repartidor, confirmas que recibiste el pedido. Si hay una diferencia, vuelve para registrarla.',
                ),
              ),
              SizedBox(
                height: (constraints.maxHeight * .6).clamp(240, 560).toDouble(),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(24),
                  child: MobileScanner(
                    onDetect: _detected,
                    errorBuilder: (context, error) => ColoredBox(
                      color: Theme.of(context).colorScheme.surfaceContainerLow,
                      child: Center(
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.all(24),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(
                                Icons.no_photography_outlined,
                                size: 40,
                              ),
                              const SizedBox(height: 16),
                              const Text(
                                'No podemos usar la cámara. Habilítala en los ajustes del dispositivo o escribe el código de entrega.',
                                textAlign: TextAlign.center,
                              ),
                              const SizedBox(height: 16),
                              FilledButton(
                                onPressed: () => Navigator.of(context).pop(),
                                style: FilledButton.styleFrom(
                                  minimumSize: const Size.fromHeight(48),
                                ),
                                child: const Text('Escribir código'),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              if (_notice != null)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Semantics(liveRegion: true, child: Text(_notice!)),
                ),
              Padding(
                padding: const EdgeInsets.all(16),
                child: TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  style: TextButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                  child: const Text('Volver sin confirmar'),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
