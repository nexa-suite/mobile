import 'dart:convert';
import 'dart:io';

const _route = '/api/v1/skus/resolve';
const _maxIdentifierLength = 160;
const _identifierTypes = <String>{'SKU_CODE', 'GTIN', 'SKU_CODE_AND_GTIN'};
const _uuidPattern =
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';

Future<void> main() async {
  try {
    final fixtureUri = Platform.script.resolve(
      '../fixtures/sku-resolver-contract.json',
    );
    final fixture = _asMap(
      jsonDecode(await File.fromUri(fixtureUri).readAsString()),
    );
    _check(fixture != null, 'fixture root must be an object');
    final contract = _asMap(fixture!['contract']);
    _check(contract != null, 'fixture contract must be an object');
    _check(contract!['method'] == 'GET', 'expected GET method changed');
    _check(contract['path'] == _route, 'expected resolver route changed');

    final cases = fixture['cases'];
    _check(
      cases is List && cases.isNotEmpty,
      'fixture cases must be a non-empty list',
    );
    var checked = 0;
    for (final rawCase in cases as List<dynamic>) {
      final testCase = _asMap(rawCase);
      _check(testCase != null, 'case $checked must be an object');
      final name = testCase!['name'];
      _check(name is String && name.isNotEmpty, 'case $checked needs a name');
      final actual = _resolveCase(testCase);
      final expected = _asMap(testCase['expected']);
      _check(expected != null, '$name: expected outcome must be an object');
      _check(
        _deepEquals(actual, expected),
        '$name: expected ${jsonEncode(expected)}, got ${jsonEncode(actual)}',
      );
      checked++;
    }
    stdout.writeln('PASS: $checked synthetic SKU resolver contract fixtures');
  } catch (error) {
    stderr.writeln('FAIL: $error');
    exitCode = 1;
  }
}

Map<String, Object?> _resolveCase(Map<String, dynamic> testCase) {
  final rawIdentifier = testCase['requestIdentifier'];
  if (rawIdentifier is! String) return _outcome('InvalidIdentifier');
  final identifier = rawIdentifier.trim();
  if (identifier.isEmpty || identifier.length > _maxIdentifierLength) {
    return _outcome('InvalidIdentifier');
  }

  switch (testCase['transport']) {
    case 'network-failure':
      return _outcome('NetworkUnavailable');
    case 'none':
      return _outcome('ServiceUnavailable');
    case 'http':
      break;
    default:
      return _outcome('ServiceUnavailable');
  }

  final status = testCase['status'];
  if (status is! int) return _outcome('ServiceUnavailable');
  if (status == 400) return _outcome('InvalidIdentifier');
  if (status == 401) return _outcome('SessionExpired');
  if (status == 403) {
    return testCase['problemCode'] == 'ACCESS_CONTEXT_INVALID'
        ? _outcome('ContextInvalidated')
        : _outcome('PermissionDenied');
  }
  if (status != 200) return _outcome('ServiceUnavailable');

  final response = _asMap(testCase['body']);
  if (response == null) return _outcome('ServiceUnavailable');
  final outcome = response['outcome'];
  final identifierType = response['identifierType'];
  final normalizedIdentifier = _requiredText(response['normalizedIdentifier']);
  final candidateCount = response['candidateCount'];
  if (outcome is! String ||
      identifierType is! String ||
      !_identifierTypes.contains(identifierType) ||
      normalizedIdentifier != identifier ||
      candidateCount is! int ||
      candidateCount < 0) {
    return _outcome('ServiceUnavailable');
  }

  switch (outcome) {
    case 'NOT_FOUND':
      return candidateCount == 0 && _hasNoProjection(response)
          ? _outcome('NotFound', identifierType: identifierType)
          : _outcome('ServiceUnavailable');
    case 'AMBIGUOUS':
      return candidateCount > 1 && _hasNoProjection(response)
          ? _outcome(
              'Ambiguous',
              candidateCount: candidateCount,
              identifierType: identifierType,
            )
          : _outcome('ServiceUnavailable');
    case 'RESOLVED':
      return _parseResolved(response, candidateCount, identifierType);
    default:
      return _outcome('ServiceUnavailable');
  }
}

Map<String, Object?> _parseResolved(
  Map<String, dynamic> response,
  int candidateCount,
  String identifierType,
) {
  if (candidateCount != 1) return _outcome('ServiceUnavailable');
  final skuId = _requiredText(response['skuId']);
  if (skuId == null || !RegExp(_uuidPattern).hasMatch(skuId)) {
    return _outcome('ServiceUnavailable');
  }
  final skuCode = _requiredText(response['skuCode']);
  final presentation = _requiredText(response['presentation']);
  final status = _requiredText(response['status']);
  if (skuCode == null || presentation == null || status == null) {
    return _outcome('ServiceUnavailable');
  }
  final sku = <String, Object?>{
    'skuId': skuId.toLowerCase(),
    'skuCode': skuCode,
    'gtin': _optionalText(response['gtin']),
    'presentation': presentation,
    'unitOfMeasure': _optionalText(response['unitOfMeasure']),
    'status': status,
    'identifierType': identifierType,
  };
  return _outcome('Resolved', sku: sku);
}

bool _hasNoProjection(Map<String, dynamic> response) =>
    response['skuId'] == null &&
    response['skuCode'] == null &&
    response['gtin'] == null &&
    response['presentation'] == null &&
    response['unitOfMeasure'] == null &&
    response['status'] == null;

String? _requiredText(Object? value) =>
    value is String && value.trim().isNotEmpty ? value : null;

String? _optionalText(Object? value) =>
    value is String && value.trim().isNotEmpty ? value : null;

Map<String, Object?> _outcome(
  String kind, {
  String? identifierType,
  int? candidateCount,
  Map<String, Object?>? sku,
}) {
  final result = <String, Object?>{'kind': kind};
  if (identifierType != null) result['identifierType'] = identifierType;
  if (candidateCount != null) result['candidateCount'] = candidateCount;
  if (sku != null) result['sku'] = sku;
  return result;
}

Map<String, dynamic>? _asMap(Object? value) =>
    value is Map<String, dynamic> ? value : null;

bool _deepEquals(Object? left, Object? right) {
  if (left is Map && right is Map) {
    if (left.length != right.length) return false;
    return left.entries.every(
      (entry) =>
          right.containsKey(entry.key) &&
          _deepEquals(entry.value, right[entry.key]),
    );
  }
  if (left is List && right is List) {
    if (left.length != right.length) return false;
    for (var index = 0; index < left.length; index++) {
      if (!_deepEquals(left[index], right[index])) return false;
    }
    return true;
  }
  return left == right;
}

void _check(bool condition, String message) {
  if (!condition) throw StateError(message);
}
