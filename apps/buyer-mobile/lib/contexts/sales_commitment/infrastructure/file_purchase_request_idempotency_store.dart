import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

import '../application/purchase_request_idempotency_store.dart';

final class FilePurchaseRequestIdempotencyStore
    implements PurchaseRequestIdempotencyStore {
  FilePurchaseRequestIdempotencyStore({
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

  @override
  Future<String> keyFor({
    required String scopeKey,
    required String draftId,
    required int version,
  }) async {
    _validateInput(scopeKey, draftId, version);
    late String result;
    await _serialized(() async {
      final entries = await _read();
      final identity = _identity(scopeKey, draftId, version);
      final existing = entries.commands[identity];
      if (existing != null) {
        if (!_uuidPattern.hasMatch(existing)) _corrupt();
        result = existing;
        return;
      }
      result = _uuid.v4();
      entries.commands[identity] = result;
      await _write(entries);
    });
    return result;
  }

  @override
  Future<void> markCompleted({
    required String scopeKey,
    required String draftId,
    required int version,
  }) async {
    _validateInput(scopeKey, draftId, version);
    await _serialized(() async {
      final entries = await _read();
      entries.commands.remove(_identity(scopeKey, draftId, version));
      await _write(entries);
    });
  }

  @override
  Future<PurchaseRequestCreationRecord?> creationRecord(String scopeKey) async {
    _validateScope(scopeKey);
    final identity = _scopeIdentity(scopeKey);
    late PurchaseRequestCreationRecord? result;
    await _serialized(() async {
      final entries = await _read();
      if (!entries.creations.containsKey(identity)) {
        result = null;
      } else {
        final draftId = entries.creations[identity];
        result = draftId == null
            ? const PurchaseRequestCreationRecord.unknown()
            : PurchaseRequestCreationRecord.known(draftId);
      }
    });
    return result;
  }

  @override
  Future<void> beginCreation(String scopeKey) async {
    _validateScope(scopeKey);
    await _serialized(() async {
      final entries = await _read();
      final identity = _scopeIdentity(scopeKey);
      if (entries.creations.containsKey(identity)) {
        throw const FormatException(
          'A previous purchase request creation still needs review.',
        );
      }
      entries.creations[identity] = null;
      await _write(entries);
    });
  }

  @override
  Future<void> recordCreatedDraft(String scopeKey, String draftId) async {
    _validateScope(scopeKey);
    if (!_uuidPattern.hasMatch(draftId)) _corrupt();
    await _serialized(() async {
      final entries = await _read();
      final identity = _scopeIdentity(scopeKey);
      if (!entries.creations.containsKey(identity)) _corrupt();
      entries.creations[identity] = draftId;
      await _write(entries);
    });
  }

  @override
  Future<void> clearCreationRecord(String scopeKey) async {
    _validateScope(scopeKey);
    await _serialized(() async {
      final entries = await _read();
      entries.creations.remove(_scopeIdentity(scopeKey));
      await _write(entries);
    });
  }

  Future<_StoredCommandState> _read() async {
    final file = await _file();
    if (!await file.exists()) return _StoredCommandState();
    try {
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map<String, dynamic>) _corrupt();
      if (decoded['schemaVersion'] != 2) {
        final legacy = <String, String>{};
        for (final entry in decoded.entries) {
          if (entry.key.length != 64 ||
              entry.value is! String ||
              !_uuidPattern.hasMatch(entry.value as String)) {
            _corrupt();
          }
          legacy[entry.key] = entry.value as String;
        }
        return _StoredCommandState(commands: legacy);
      }
      final rawCommands = decoded['commands'];
      final rawCreations = decoded['creations'];
      if (rawCommands is! Map<String, dynamic> ||
          rawCreations is! Map<String, dynamic>) {
        _corrupt();
      }
      final commands = <String, String>{};
      for (final entry in rawCommands.entries) {
        if (entry.key.length != 64 ||
            entry.value is! String ||
            !_uuidPattern.hasMatch(entry.value as String)) {
          _corrupt();
        }
        commands[entry.key] = entry.value as String;
      }
      final creations = <String, String?>{};
      for (final entry in rawCreations.entries) {
        if (entry.key.length != 64 ||
            (entry.value != null &&
                (entry.value is! String ||
                    !_uuidPattern.hasMatch(entry.value as String)))) {
          _corrupt();
        }
        creations[entry.key] = entry.value as String?;
      }
      return _StoredCommandState(commands: commands, creations: creations);
    } on FormatException {
      _corrupt();
    }
  }

  Future<void> _write(_StoredCommandState entries) async {
    final file = await _file();
    if (entries.commands.isEmpty && entries.creations.isEmpty) {
      if (await file.exists()) await file.delete();
      return;
    }
    final temporary = File('${file.path}.tmp');
    await temporary.writeAsString(
      jsonEncode({
        'schemaVersion': 2,
        'commands': entries.commands,
        'creations': entries.creations,
      }),
      flush: true,
    );
    await temporary.rename(file.path);
  }

  Future<File> _file() async {
    final directory = await _directoryProvider();
    await directory.create(recursive: true);
    return File('${directory.path}/buyer-purchase-request-commands.json');
  }

  String _identity(String scopeKey, String draftId, int version) =>
      sha256.convert(utf8.encode('$scopeKey|$draftId|$version')).toString();

  String _scopeIdentity(String scopeKey) =>
      sha256.convert(utf8.encode(scopeKey)).toString();

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

  void _validateInput(String scopeKey, String draftId, int version) {
    if (!_uuidPattern.hasMatch(draftId) || version < 0) {
      throw const FormatException(
        'Purchase request command identity is invalid.',
      );
    }
    _validateScope(scopeKey);
  }

  void _validateScope(String scopeKey) {
    if (scopeKey.isEmpty) {
      throw const FormatException('Purchase request scope is invalid.');
    }
  }

  Never _corrupt() => throw const FormatException(
    'Stored purchase request command identity cannot be read safely.',
  );
}

final class _StoredCommandState {
  _StoredCommandState({
    Map<String, String>? commands,
    Map<String, String?>? creations,
  }) : commands = commands ?? {},
       creations = creations ?? {};

  final Map<String, String> commands;
  final Map<String, String?> creations;
}
