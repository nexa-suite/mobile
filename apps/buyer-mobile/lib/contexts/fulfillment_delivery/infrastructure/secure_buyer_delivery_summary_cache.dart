import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../application/buyer_delivery_summary_cache.dart';

abstract interface class BuyerDeliverySummarySecureStorage {
  Future<String?> read({required String key});
  Future<void> write({required String key, required String value});
  Future<void> delete({required String key});
}

final class FlutterBuyerDeliverySummarySecureStorage
    implements BuyerDeliverySummarySecureStorage {
  FlutterBuyerDeliverySummarySecureStorage([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read({required String key}) => _storage.read(key: key);

  @override
  Future<void> write({required String key, required String value}) =>
      _storage.write(key: key, value: value);

  @override
  Future<void> delete({required String key}) => _storage.delete(key: key);
}

/// Encrypted, bounded cache of the least-sensitive Buyer delivery list fields.
/// It is only a read-only fallback and never supplies detail or command data.
final class SecureBuyerDeliverySummaryCache
    implements BuyerDeliverySummaryCache {
  SecureBuyerDeliverySummaryCache({
    BuyerDeliverySummarySecureStorage? secureStorage,
  }) : _secureStorage =
           secureStorage ?? FlutterBuyerDeliverySummarySecureStorage();

  static const _schemaVersion = 1;
  static const maximumItems = 25;

  static final _uuid = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
  );
  static final _orderNumber = RegExp(r'^[A-Za-z0-9._-]{1,64}$');
  static final _status = RegExp(r'^[A-Z][A-Z0-9_]{0,63}$');

  final BuyerDeliverySummarySecureStorage _secureStorage;
  final Set<String> _suppressedKeys = {};

  @override
  Future<BuyerDeliverySummaryCacheSnapshot?> read({
    required BuyerDeliverySummaryCacheScope scope,
  }) async {
    final key = _key(scope);
    if (_suppressedKeys.contains(key)) return null;
    final encoded = await _secureStorage.read(key: key);
    if (encoded == null) return null;

    try {
      final value = jsonDecode(encoded);
      if (value is! Map<String, dynamic> ||
          value['schemaVersion'] != _schemaVersion ||
          value['scopeFingerprint'] != _scopeFingerprint(scope)) {
        _invalid();
      }
      if (value['suppressed'] == true) return null;
      final rawAsOf = value['asOf'];
      final rawItems = value['items'];
      final asOf = rawAsOf is String ? DateTime.tryParse(rawAsOf) : null;
      if (asOf == null || rawItems is! List || rawItems.length > maximumItems) {
        _invalid();
      }
      final items = rawItems.map(_decodeItem).toList(growable: false);
      return BuyerDeliverySummaryCacheSnapshot(items: items, asOf: asOf);
    } catch (_) {
      await _secureStorage.delete(key: key);
      return null;
    }
  }

  @override
  Future<void> write({
    required BuyerDeliverySummaryCacheScope scope,
    required BuyerDeliverySummaryCacheSnapshot snapshot,
  }) async {
    final key = _key(scope);
    if (snapshot.items.length > maximumItems) _invalid();
    for (final item in snapshot.items) {
      _validateItem(item);
    }
    await _secureStorage.write(
      key: key,
      value: jsonEncode({
        'schemaVersion': _schemaVersion,
        'scopeFingerprint': _scopeFingerprint(scope),
        'asOf': snapshot.asOf.toUtc().toIso8601String(),
        'items': [
          for (final item in snapshot.items)
            {
              'deliveryId': item.deliveryId,
              'orderNumber': item.orderNumber,
              'status': item.status,
              'updatedAt': item.updatedAt.toUtc().toIso8601String(),
            },
        ],
      }),
    );
    _suppressedKeys.remove(key);
  }

  @override
  Future<void> delete({required BuyerDeliverySummaryCacheScope scope}) async {
    final key = _key(scope);
    _suppressedKeys.add(key);
    try {
      await _secureStorage.write(
        key: key,
        value: jsonEncode({
          'schemaVersion': _schemaVersion,
          'scopeFingerprint': _scopeFingerprint(scope),
          'suppressed': true,
        }),
      );
    } catch (_) {
      // Still attempt deletion; the in-memory fence covers failed storage IO.
    }
    await _secureStorage.delete(key: key);
  }

  BuyerDeliverySummaryProjection _decodeItem(Object? value) {
    if (value is! Map<String, dynamic>) _invalid();
    final deliveryId = value['deliveryId'];
    final orderNumber = value['orderNumber'];
    final status = value['status'];
    final updatedAtValue = value['updatedAt'];
    final updatedAt = updatedAtValue is String
        ? DateTime.tryParse(updatedAtValue)
        : null;
    if (deliveryId is! String ||
        orderNumber is! String ||
        status is! String ||
        updatedAt == null) {
      _invalid();
    }
    final item = BuyerDeliverySummaryProjection(
      deliveryId: deliveryId,
      orderNumber: orderNumber,
      status: status,
      updatedAt: updatedAt,
    );
    _validateItem(item);
    return item;
  }

  void _validateItem(BuyerDeliverySummaryProjection item) {
    if (!_uuid.hasMatch(item.deliveryId) ||
        !_orderNumber.hasMatch(item.orderNumber) ||
        !_status.hasMatch(item.status)) {
      _invalid();
    }
  }

  String _key(BuyerDeliverySummaryCacheScope scope) {
    _validateScope(scope);
    return 'buyer_delivery_summary_v1_${_scopeFingerprint(scope)}';
  }

  String _scopeFingerprint(BuyerDeliverySummaryCacheScope scope) => sha256
      .convert(
        utf8.encode(
          '${scope.userId}|${scope.tenantId}|${scope.workspaceId}|'
          '${scope.membershipId}|${scope.authorityFingerprint}',
        ),
      )
      .toString();

  void _validateScope(BuyerDeliverySummaryCacheScope scope) {
    if (!_uuid.hasMatch(scope.userId) ||
        !_uuid.hasMatch(scope.tenantId) ||
        !_uuid.hasMatch(scope.workspaceId) ||
        !_uuid.hasMatch(scope.membershipId) ||
        scope.authorityFingerprint.trim().isEmpty ||
        scope.authorityFingerprint.length > 2048) {
      _invalid();
    }
  }

  Never _invalid() => throw const FormatException(
    'Stored Buyer delivery summary cannot be read safely.',
  );
}
