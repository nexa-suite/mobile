import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// The camera only reads a QR. Acceptance belongs to the receipt workflow.
final class BuyerDeliveryQrScanner extends StatefulWidget {
  const BuyerDeliveryQrScanner({super.key});
  @override
  State<BuyerDeliveryQrScanner> createState() => _BuyerDeliveryQrScannerState();
}

final class _BuyerDeliveryQrScannerState extends State<BuyerDeliveryQrScanner> {
  final _code = TextEditingController();
  bool _manual = false;
  bool _returned = false;
  String? _notice;

  void _detected(BarcodeCapture capture) {
    if (!mounted ||
        _returned ||
        _manual ||
        WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed) {
      return;
    }
    for (final barcode in capture.barcodes) {
      final value = barcode.rawValue?.trim();
      if (barcode.format != BarcodeFormat.qrCode || value == null) continue;
      _submit(value);
      return;
    }
  }

  void _submit(String rawValue) {
    if (!mounted ||
        _returned ||
        WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed) {
      return;
    }
    final value = rawValue.trim();
    if (value.isEmpty || value.length > 400 || value.contains(RegExp(r'\s'))) {
      setState(() => _notice = 'Usa el código de entrega sin espacios.');
      return;
    }
    _returned = true;
    Navigator.of(context).pop(value);
  }

  void _openManual() {
    if (!mounted || _returned) return;
    setState(() {
      _manual = true;
      _notice = null;
    });
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
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
              if (_manual)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Column(
                    children: [
                      TextField(
                        controller: _code,
                        autocorrect: false,
                        enableSuggestions: false,
                        maxLength: 400,
                        decoration: const InputDecoration(
                          labelText: 'Código de entrega',
                        ),
                        onSubmitted: _submit,
                      ),
                      FilledButton(
                        onPressed: () => _submit(_code.text),
                        child: const Text('Continuar'),
                      ),
                    ],
                  ),
                )
              else
                SizedBox(
                  height: (constraints.maxHeight * .6)
                      .clamp(240, 560)
                      .toDouble(),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(24),
                    child: MobileScanner(
                      onDetect: _detected,
                      errorBuilder: (context, error) => ColoredBox(
                        color: Theme.of(context)
                            .colorScheme
                            .surfaceContainerLow,
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
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              if (!_manual)
                TextButton(
                  onPressed: _openManual,
                  child: const Text('Escribir código'),
                ),
              if (_notice != null)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: Semantics(liveRegion: true, child: Text(_notice!)),
                ),
              Padding(
                padding: const EdgeInsets.all(16),
                child: TextButton(
                  onPressed: () {
                    if (_returned) return;
                    _returned = true;
                    Navigator.of(context).pop();
                  },
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
