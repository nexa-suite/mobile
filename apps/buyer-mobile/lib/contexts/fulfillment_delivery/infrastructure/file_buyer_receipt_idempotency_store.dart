import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

import '../application/buyer_receipt_idempotency_store.dart';

final class FileBuyerReceiptIdempotencyStore
    implements BuyerReceiptIdempotencyStore {
  FileBuyerReceiptIdempotencyStore({
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
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  }) async {
    if (scopeKey.trim().isEmpty ||
        !_uuidPattern.hasMatch(deliveryId) ||
        !_uuidPattern.hasMatch(attemptId) ||
        !_fingerprintPattern.hasMatch(payloadFingerprint)) {
      _corrupt();
    }
    late String result;
    await _serialized(() async {
      final entries = await _read();
      final identity = _identity(scopeKey, deliveryId, attemptId);
      final existing = entries[identity];
      if (existing != null) {
        if (!_fingerprintPattern.hasMatch(existing.fingerprint) ||
            !_uuidPattern.hasMatch(existing.key)) {
          _corrupt();
        }
        if (existing.fingerprint != payloadFingerprint) {
          throw const BuyerReceiptCommandConflict();
        }
        result = existing.key;
        return;
      }
      result = _uuid.v4();
      entries[identity] = _Entry(payloadFingerprint, result);
      await _write(entries);
    });
    return result;
  }

  @override
  Future<void> release({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  }) async {
    if (scopeKey.trim().isEmpty ||
        !_uuidPattern.hasMatch(deliveryId) ||
        !_uuidPattern.hasMatch(attemptId) ||
        !_fingerprintPattern.hasMatch(payloadFingerprint)) {
      _corrupt();
    }
    await _serialized(() async {
      final entries = await _read();
      final identity = _identity(scopeKey, deliveryId, attemptId);
      final existing = entries[identity];
      if (existing == null) return;
      if (existing.fingerprint != payloadFingerprint) {
        throw const BuyerReceiptCommandConflict();
      }
      entries.remove(identity);
      await _write(entries);
    });
  }

  Future<Map<String, _Entry>> _read() async {
    final file = await _file();
    if (!await file.exists()) return {};
    try {
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map<String, dynamic> || decoded['schemaVersion'] != 1) {
        _corrupt();
      }
      final rawEntries = decoded['entries'];
      if (rawEntries is! Map<String, dynamic>) _corrupt();
      final entries = <String, _Entry>{};
      for (final entry in rawEntries.entries) {
        if (entry.key.length != 64 || entry.value is! Map<String, dynamic>) {
          _corrupt();
        }
        final value = entry.value as Map<String, dynamic>;
        final fingerprint = value['payloadFingerprint'];
        final key = value['idempotencyKey'];
        if (fingerprint is! String ||
            !_fingerprintPattern.hasMatch(fingerprint) ||
            key is! String ||
            !_uuidPattern.hasMatch(key)) {
          _corrupt();
        }
        entries[entry.key] = _Entry(fingerprint, key);
      }
      return entries;
    } on FormatException {
      _corrupt();
    }
  }

  Future<void> _write(Map<String, _Entry> entries) async {
    final file = await _file();
    final temporary = File('${file.path}.tmp');
    await temporary.writeAsString(
      jsonEncode({
        'schemaVersion': 1,
        'entries': {
          for (final entry in entries.entries)
            entry.key: {
              'payloadFingerprint': entry.value.fingerprint,
              'idempotencyKey': entry.value.key,
            },
        },
      }),
      flush: true,
    );
    await temporary.rename(file.path);
  }

  Future<File> _file() async {
    final directory = await _directoryProvider();
    await directory.create(recursive: true);
    return File('${directory.path}/buyer-receipt-retries.json');
  }

  String _identity(String scopeKey, String deliveryId, String attemptId) =>
      sha256
          .convert(utf8.encode('$scopeKey|$deliveryId|$attemptId'))
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

  Never _corrupt() => throw const FormatException(
    'Stored Buyer receipt retry identity cannot be read safely.',
  );
}

final class _Entry {
  const _Entry(this.fingerprint, this.key);

  final String fingerprint;
  final String key;
}
