import '../application/business_document_export_port.dart';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import '../application/business_documents_repository.dart';
import 'business_documents_view_model.dart';

final class BusinessDocumentDetailPage extends StatelessWidget {
  const BusinessDocumentDetailPage({super.key, this.exporter});

  final BusinessDocumentExportPort? exporter;

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<BusinessDocumentDetailViewModel>();
    final document = viewModel.document;
    if (document == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Documento')),
        body: SafeArea(
          top: false,
          child: switch (viewModel.status) {
            BusinessDocumentDetailStatus.loading => const Center(
              child: CircularProgressIndicator(),
            ),
            BusinessDocumentDetailStatus.permissionDenied ||
            BusinessDocumentDetailStatus.unavailable => _DocumentDetailMessage(
              message:
                  viewModel.message ?? 'Este documento no está disponible.',
              onRetry:
                  viewModel.status == BusinessDocumentDetailStatus.unavailable
                  ? viewModel.load
                  : null,
            ),
            BusinessDocumentDetailStatus.current => const SizedBox.shrink(),
          },
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text(document.documentNumber ?? 'Documento')),
      body: SafeArea(
        top: false,
        child: NexaPageWidth(
          padding: EdgeInsets.zero,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              NexaPageIntro(
                title: document.documentNumber ?? 'Documento',
                description: 'Consulta y guarda el documento de tu compra.',
                icon: Icons.description_outlined,
              ),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _documentType(document.documentType),
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 8),
                      NexaStatusBadge(document.status),
                      _Metadata(label: 'Formato', value: document.format),
                      _Metadata(
                        label: 'Tamaño',
                        value: _byteSize(document.byteSize),
                      ),
                      _Metadata(
                        label: 'Creado',
                        value: _dateLabel(document.createdAt),
                      ),
                      if (document.generatedAt != null)
                        _Metadata(
                          label: 'Generado',
                          value: _dateLabel(document.generatedAt!),
                        ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),
              if (viewModel.canDownload)
                FilledButton.icon(
                  onPressed:
                      viewModel.downloadStatus ==
                          BusinessDocumentDownloadStatus.loading
                      ? null
                      : viewModel.download,
                  icon:
                      viewModel.downloadStatus ==
                          BusinessDocumentDownloadStatus.loading
                      ? const SizedBox.square(
                          dimension: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.download_outlined),
                  label: Text(
                    viewModel.downloadStatus ==
                            BusinessDocumentDownloadStatus.loading
                        ? 'Cargando documento…'
                        : 'Cargar documento',
                  ),
                )
              else
                _AvailabilityMessage(document: document, viewModel: viewModel),
              if (viewModel.downloadStatus ==
                      BusinessDocumentDownloadStatus.permissionDenied ||
                  viewModel.downloadStatus ==
                      BusinessDocumentDownloadStatus.unavailable)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    viewModel.message ?? 'No se pudo verificar el contenido.',
                    textAlign: TextAlign.center,
                  ),
                ),
              if (viewModel.content case final content?) ...[
                const SizedBox(height: 16),
                Card(
                  color: Theme.of(context).colorScheme.primaryContainer,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Documento listo'),
                        const SizedBox(height: 8),
                        Text(
                          '${document.format} · ${_byteSize(content.bytes.length)}',
                        ),
                        if (exporter != null) ...[
                          const SizedBox(height: 16),
                          SizedBox(
                            width: double.infinity,
                            child: FilledButton.icon(
                              onPressed:
                                  viewModel.exporting || !viewModel.canDownload
                                  ? null
                                  : () => viewModel.saveDocument(exporter!),
                              icon: const Icon(Icons.save_alt),
                              label: Text(
                                viewModel.exporting
                                    ? 'Guardando…'
                                    : 'Guardar documento',
                              ),
                            ),
                          ),
                        ],
                        if (viewModel.exportMessage case final message?)
                          Padding(
                            padding: const EdgeInsets.only(top: 12),
                            child: Text(message),
                          ),
                        Align(
                          alignment: Alignment.centerRight,
                          child: TextButton.icon(
                            onPressed: viewModel.discardContent,
                            icon: const Icon(Icons.delete_outline),
                            label: const Text('Cerrar documento'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

final class _Metadata extends StatelessWidget {
  const _Metadata({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 86,
          child: Text(label, style: Theme.of(context).textTheme.labelMedium),
        ),
        Expanded(child: Text(value)),
      ],
    ),
  );
}

final class _AvailabilityMessage extends StatelessWidget {
  const _AvailabilityMessage({required this.document, required this.viewModel});

  final BusinessDocumentProjection document;
  final BusinessDocumentDetailViewModel viewModel;

  @override
  Widget build(BuildContext context) {
    final text = document.contentAvailable
        ? viewModel.downloadExceedsLimit
              ? 'El documento supera el límite de descarga protegido.'
              : viewModel.downloadCapabilityGranted
              ? 'El contenido puede verificarse al solicitarlo.'
              : 'No tienes permiso para cargar el contenido.'
        : switch (document.status) {
            'REQUESTED' ||
            'GENERATING' => 'El documento todavía se está preparando.',
            _ => 'Este estado no tiene contenido disponible.',
          };
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Text(text, textAlign: TextAlign.center),
      ),
    );
  }
}

final class _DocumentDetailMessage extends StatelessWidget {
  const _DocumentDetailMessage({required this.message, this.onRetry});

  final String message;
  final Future<void> Function()? onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(message, textAlign: TextAlign.center),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onRetry, child: const Text('Reintentar')),
          ],
        ],
      ),
    ),
  );
}

String _dateLabel(DateTime value) {
  final local = value.toLocal();
  return '${local.year.toString().padLeft(4, '0')}-'
      '${local.month.toString().padLeft(2, '0')}-'
      '${local.day.toString().padLeft(2, '0')} '
      '${local.hour.toString().padLeft(2, '0')}:'
      '${local.minute.toString().padLeft(2, '0')}';
}

String _byteSize(int bytes) => bytes < 1024
    ? '$bytes B'
    : bytes < 1024 * 1024
    ? '${(bytes / 1024).toStringAsFixed(1)} KB'
    : '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';

String _documentType(String value) => switch (value.toUpperCase()) {
  'INVOICE' => 'Factura',
  'DISPATCH_GUIDE' || 'DELIVERY_GUIDE' => 'Guía de entrega',
  'PAYMENT_RECEIPT' => 'Comprobante de pago',
  'SALES_ORDER_SUMMARY' => 'Resumen del pedido',
  _ => 'Documento de compra',
};
