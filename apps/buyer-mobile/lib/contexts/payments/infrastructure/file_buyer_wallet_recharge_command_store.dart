import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

import '../application/buyer_wallet_recharge_command_store.dart';

final class FileBuyerWalletRechargeCommandStore
    implements BuyerWalletRechargeCommandStore {
  FileBuyerWalletRechargeCommandStore({
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
  static final _amountPattern = RegExp(r'^(?:0|[1-9]\d{0,5})\.\d{2}$');

  @override
  Future<BuyerWalletRechargeCommand?> load({required String scopeKey}) async {
    _validateScope(scopeKey);
    late BuyerWalletRechargeCommand? result;
    await _serialized(() async {
      result = (await _read())[_scopeIdentity(scopeKey)];
    });
    return result;
  }

  @override
  Future<BuyerWalletRechargeCommand> prepare({
    required String scopeKey,
    required String amount,
  }) async {
    _validateScope(scopeKey);
    if (!_isAmount(amount)) _corrupt();
    late BuyerWalletRechargeCommand result;
    await _serialized(() async {
      final entries = await _read();
      final identity = _scopeIdentity(scopeKey);
      final existing = entries[identity];
      if (existing != null) {
        result = existing;
        return;
      }
      result = BuyerWalletRechargeCommand(
        amount: amount,
        idempotencyKey: _uuid.v4(),
      );
      entries[identity] = result;
      await _write(entries);
    });
    return result;
  }

  @override
  Future<bool> recordCreated({
    required String scopeKey,
    required String idempotencyKey,
    required String rechargeId,
  }) async {
    _validateScope(scopeKey);
    if (!_uuidPattern.hasMatch(idempotencyKey) ||
        !_uuidPattern.hasMatch(rechargeId)) {
      _corrupt();
    }
    var recorded = false;
    await _serialized(() async {
      final entries = await _read();
      final identity = _scopeIdentity(scopeKey);
      final existing = entries[identity];
      if (existing == null || existing.idempotencyKey != idempotencyKey) {
        return;
      }
      if (existing.rechargeId != null && existing.rechargeId != rechargeId) {
        return;
      }
      entries[identity] = existing.withRechargeId(rechargeId);
      await _write(entries);
      recorded = true;
    });
    return recorded;
  }

  @override
  Future<bool> clear({
    required String scopeKey,
    required String idempotencyKey,
  }) async {
    _validateScope(scopeKey);
    if (!_uuidPattern.hasMatch(idempotencyKey)) _corrupt();
    var cleared = true;
    await _serialized(() async {
      final entries = await _read();
      final identity = _scopeIdentity(scopeKey);
      final existing = entries[identity];
      if (existing == null) return;
      if (existing.idempotencyKey != idempotencyKey) {
        cleared = false;
        return;
      }
      entries.remove(identity);
      await _write(entries);
    });
    return cleared;
  }

  Future<Map<String, BuyerWalletRechargeCommand>> _read() async {
    final file = await _file();
    if (!await file.exists()) return {};
    try {
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map<String, dynamic> || decoded['schemaVersion'] != 1) {
        _corrupt();
      }
      final rawEntries = decoded['entries'];
      if (rawEntries is! Map<String, dynamic>) _corrupt();
      final entries = <String, BuyerWalletRechargeCommand>{};
      for (final entry in rawEntries.entries) {
        final value = entry.value;
        if (entry.key.length != 64 || value is! Map<String, dynamic>) {
          _corrupt();
        }
        final amount = value['amount'];
        final idempotencyKey = value['idempotencyKey'];
        final rechargeId = value['rechargeId'];
        if (amount is! String || !_isAmount(amount) ||
            idempotencyKey is! String || !_uuidPattern.hasMatch(idempotencyKey) ||
            (rechargeId != null &&
                (rechargeId is! String || !_uuidPattern.hasMatch(rechargeId)))) {
          _corrupt();
        }
        entries[entry.key] = BuyerWalletRechargeCommand(
          amount: amount,
          idempotencyKey: idempotencyKey,
          rechargeId: rechargeId as String?,
        );
      }
      return entries;
    } on FormatException {
      _corrupt();
    }
  }

  Future<void> _write(Map<String, BuyerWalletRechargeCommand> entries) async {
    final file = await _file();
    if (entries.isEmpty) {
      if (await file.exists()) await file.delete();
      return;
    }
    final temporary = File('${file.path}.tmp');
    await temporary.writeAsString(
      jsonEncode({
        'schemaVersion': 1,
        'entries': {
          for (final entry in entries.entries)
            entry.key: {
              'amount': entry.value.amount,
              'idempotencyKey': entry.value.idempotencyKey,
              'rechargeId': entry.value.rechargeId,
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
    return File('${directory.path}/buyer-wallet-recharge-commands.json');
  }

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

  void _validateScope(String scopeKey) {
    if (scopeKey.isEmpty || scopeKey.length > 512) _corrupt();
  }

  bool _isAmount(String amount) {
    if (!_amountPattern.hasMatch(amount)) return false;
    final parts = amount.split('.');
    final whole = int.tryParse(parts[0]);
    final fraction = int.tryParse(parts[1]);
    if (whole == null || fraction == null) return false;
    final minor = whole * 100 + fraction;
    return minor > 0 && minor <= 99999999;
  }

  Never _corrupt() => throw const FormatException(
    'Stored Buyer wallet recharge recovery data cannot be read safely.',
  );
}
