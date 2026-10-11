import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

import '../application/payment_report_idempotency_store.dart';

final class FilePaymentReportIdempotencyStore
    implements PaymentReportIdempotencyStore {
  FilePaymentReportIdempotencyStore({
    Future<Directory> Function()? directoryProvider,
    Uuid? uuid,
  }) : _directoryProvider = directoryProvider ?? getApplicationSupportDirectory,
       _uuid = uuid ?? const Uuid();

  final Future<Directory> Function() _directoryProvider;
  final Uuid _uuid;
  Future<void> _tail = Future.value();

  static final _uuidPattern = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );
  static final _fingerprintPattern = RegExp(r'^[0-9a-fA-F]{64}$');

  @override
  Future<String> keyFor({
    required String scopeKey,
    required String receivableId,
    required String referenceFingerprint,
  }) async {
    _validate(scopeKey, receivableId, referenceFingerprint);
    late String result;
    await _serialized(() async {
      final entries = await _read();
      final identity = _identity(scopeKey, receivableId, referenceFingerprint);
      final existing = entries[identity];
      if (existing != null) {
        if (!_uuidPattern.hasMatch(existing)) _corrupt();
        result = existing;
        return;
      }
      result = _uuid.v4();
      entries[identity] = result;
      await _write(entries);
    });
    return result;
  }

  Future<Map<String, String>> _read() async {
    final file = await _file();
    if (!await file.exists()) return {};
    try {
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map<String, dynamic> || decoded['schemaVersion'] != 1) {
        _corrupt();
      }
      final rawEntries = decoded['entries'];
      if (rawEntries is! Map<String, dynamic>) _corrupt();
      final entries = <String, String>{};
      for (final entry in rawEntries.entries) {
        if (entry.key.length != 64 ||
            entry.value is! String ||
            !_uuidPattern.hasMatch(entry.value as String)) {
          _corrupt();
        }
        entries[entry.key] = entry.value as String;
      }
      return entries;
    } on FormatException {
      _corrupt();
    }
  }

  Future<void> _write(Map<String, String> entries) async {
    final file = await _file();
    if (entries.isEmpty) {
      if (await file.exists()) await file.delete();
      return;
    }
    final temporary = File('${file.path}.tmp');
    await temporary.writeAsString(
      jsonEncode({'schemaVersion': 1, 'entries': entries}),
      flush: true,
    );
    await temporary.rename(file.path);
  }

  Future<File> _file() async {
    final directory = await _directoryProvider();
    await directory.create(recursive: true);
    return File('${directory.path}/buyer-payment-report-retries.json');
  }

  String _identity(
    String scopeKey,
    String receivableId,
    String referenceFingerprint,
  ) => sha256
      .convert(utf8.encode('$scopeKey|$receivableId|$referenceFingerprint'))
      .toString();

  Future<void> _serialized(Future<void> Function() action) async {
    final completer = Completer<void>();
    final previous = _tail;
    _tail = completer.future;
    await previous;
    try {
      await action();
    } finally {
      completer.complete();
    }
  }

  void _validate(String scopeKey, String receivableId, String fingerprint) {
    if (scopeKey.isEmpty ||
        !_uuidPattern.hasMatch(receivableId) ||
        !_fingerprintPattern.hasMatch(fingerprint)) {
      _corrupt();
    }
  }

  Never _corrupt() => throw const FormatException(
    'Stored payment retry identity cannot be read safely.',
  );
}
