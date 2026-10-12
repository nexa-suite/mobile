import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../application/business_documents_repository.dart';
import 'business_documents_view_model.dart';

final class BusinessDocumentDetailPage extends StatelessWidget {
  const BusinessDocumentDetailPage({super.key});

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<BusinessDocumentDetailViewModel>();
    final document = viewModel.document;
    if (document == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Documento')),
        body: switch (viewModel.status) {
          BusinessDocumentDetailStatus.loading => const Center(
            child: CircularProgressIndicator(),
          ),
          BusinessDocumentDetailStatus.permissionDenied ||
          BusinessDocumentDetailStatus.unavailable => _DocumentDetailMessage(
            message: viewModel.message ?? 'Este documento no está disponible.',
            onRetry:
                viewModel.status == BusinessDocumentDetailStatus.unavailable
                ? viewModel.load
                : null,
          ),
          BusinessDocumentDetailStatus.current => const SizedBox.shrink(),
        },
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text(document.documentNumber ?? 'Documento')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    document.documentType,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  _Metadata(label: 'Estado', value: document.status),
                  _Metadata(label: 'Formato', value: document.format),
                  _Metadata(label: 'Versión', value: '${document.version}'),
                  _Metadata(
                    label: 'Tamaño',
                    value: _byteSize(document.byteSize),
                  ),
                  _Metadata(
                    label: 'Asunto',
                    value: '${document.subjectType} · ${document.subjectId}',
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
                  if (document.checksumSha256 != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      'SHA-256',
                      style: Theme.of(context).textTheme.labelLarge,
                    ),
                    const SizedBox(height: 4),
                    SelectableText(
                      document.checksumSha256!,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
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
                    ? 'Verificando contenido…'
                    : 'Cargar contenido protegido',
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
                    const Row(
                      children: [
                        Icon(Icons.verified_outlined),
                        SizedBox(width: 8),
                        Expanded(child: Text('Contenido verificado')),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${content.bytes.length} bytes · SHA-256 ${content.sha256}',
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'El contenido permanece solo en la memoria de esta vista. '
                      'No se guardó en el dispositivo.',
                    ),
                    Align(
                      alignment: Alignment.centerRight,
                      child: TextButton.icon(
                        onPressed: viewModel.discardContent,
                        icon: const Icon(Icons.delete_outline),
                        label: const Text('Descartar contenido'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
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
