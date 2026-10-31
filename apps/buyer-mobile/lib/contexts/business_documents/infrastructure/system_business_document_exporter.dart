import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';

import '../application/business_document_export_port.dart';
import '../application/business_documents_repository.dart';

final class SystemBusinessDocumentExporter
    implements BusinessDocumentExportPort {
  @override
  Future<bool> save(BusinessDocumentContentProjection content) async {
    final document = content.document;
    final extension = switch (document.format.toUpperCase()) {
      'PDF' => 'pdf',
      'XML' => 'xml',
      'CSV' => 'csv',
      _ => null,
    };
    if (extension == null ||
        content.bytes.length > maxProtectedBusinessDocumentBytes) {
      throw const FormatException('Unsupported document export.');
    }
    final bytes = Uint8List.fromList(content.bytes);
    final name = (document.documentNumber ?? 'documento').replaceAll(
      RegExp(r'[^a-zA-Z0-9_-]'),
      '_',
    );
    try {
      return await FilePicker.saveFile(
            fileName:
                '${name.substring(0, name.length.clamp(0, 80))}.$extension',
            bytes: bytes,
            mimeType: document.contentType ?? 'application/octet-stream',
            dialogTitle: 'Guardar documento',
          ) !=
          null;
    } finally {
      bytes.fillRange(0, bytes.length, 0);
    }
  }
}
