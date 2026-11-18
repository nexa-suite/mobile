import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_access_repository.dart';
import '../application/buyer_mobile_terms_repository.dart';

final class BuyerMobileTermsRepositoryImpl
    implements BuyerMobileTermsRepository {
  BuyerMobileTermsRepositoryImpl(this._api, this._access);

  final NexaApiClient _api;
  final BuyerAccessRepository _access;

  @override
  Future<BuyerMobileTermsDocument> fetchPublicTerms() async {
    final response = await _readTerms(
      () => _api.get('/legal/mobile-terms', authenticated: false),
    );
    return _parseDocument(response.body);
  }

  @override
  Future<BuyerMobileTermsStatus> fetchCurrentStatus() async {
    final identity = _activeIdentityKey;
    if (identity == null) _throwSessionRequired();
    final response = await _readTerms(
      () => _api.get('/me/legal-acceptance', refreshAfterUnauthorized: true),
    );
    _requireCurrentIdentity(identity);
    final document = _parseDocument(response.body);
    final accepted = response.body['accepted'];
    final acceptedAt = _parseDate(response.body['acceptedAt']);
    if (accepted is! bool || accepted != (acceptedAt != null)) {
      _throwInvalidResponse();
    }
    return BuyerMobileTermsStatus(
      document: document,
      accepted: accepted,
      acceptedAt: acceptedAt,
    );
  }

  @override
  Future<BuyerMobileTermsAcceptance> accept(
    BuyerMobileTermsDocument document,
  ) async {
    final identity = _activeIdentityKey;
    if (identity == null) _throwSessionRequired();
    final response = await _readTerms(
      () => _api.post(
        '/me/legal-acceptance',
        body: {
          'documentId': document.documentId,
          'version': document.version,
          'contentDigest': document.contentDigest,
        },
      ),
    );
    _requireCurrentIdentity(identity);
    final body = response.body;
    final returnedDocument = _parseDocument(body);
    final acceptedAt = _parseDate(body['acceptedAt']);
    if (body['accepted'] != true ||
        acceptedAt == null ||
        returnedDocument.documentId != document.documentId ||
        returnedDocument.version != document.version ||
        returnedDocument.contentDigest != document.contentDigest) {
      _throwInvalidResponse();
    }
    return BuyerMobileTermsAcceptance(
      documentId: document.documentId,
      version: document.version,
      contentDigest: document.contentDigest,
      accepted: true,
      acceptedAt: acceptedAt,
    );
  }

  String? get _activeIdentityKey {
    final snapshot = _access.snapshot;
    if (!snapshot.hasActiveBuyerScope) return null;
    final userId = snapshot.currentContext!.userId;
    if (userId == null || userId.isEmpty) return null;
    return '${snapshot.authorityEpoch}|$userId|'
        '${snapshot.currentContext!.authorityFingerprint}';
  }

  void _requireCurrentIdentity(String expected) {
    if (_activeIdentityKey != expected) {
      throw BuyerMobileTermsFailure.accessDenied;
    }
  }

  BuyerMobileTermsDocument _parseDocument(Map<String, Object?> body) {
    final documentId = body['documentId'];
    final version = body['version'];
    final content = body['content'];
    final contentDigest = body['contentDigest'];
    if (documentId is! String ||
        documentId != 'mobile-terms' ||
        version is! String ||
        version.isEmpty ||
        version.length > 100 ||
        content is! String ||
        content.isEmpty ||
        contentDigest is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(contentDigest) ||
        sha256.convert(utf8.encode(content)).toString() != contentDigest) {
      _throwInvalidResponse();
    }
    return BuyerMobileTermsDocument(
      documentId: documentId,
      version: version,
      content: content,
      contentDigest: contentDigest,
    );
  }

  DateTime? _parseDate(Object? value) {
    if (value == null) return null;
    if (value is! String) _throwInvalidResponse();
    return DateTime.tryParse(value) ?? _throwInvalidResponse();
  }

  Future<T> _readTerms<T>(Future<T> Function() request) async {
    try {
      return await request();
    } on NexaApiFailure catch (failure) {
      throw switch (failure.statusCode) {
        401 => BuyerMobileTermsFailure.sessionRequired,
        403 => BuyerMobileTermsFailure.accessDenied,
        409 => BuyerMobileTermsFailure.documentChanged,
        null when failure.retryable =>
          BuyerMobileTermsFailure.connectionUnavailable,
        _ => BuyerMobileTermsFailure.unconfirmed,
      };
    }
  }

  Never _throwInvalidResponse() => throw BuyerMobileTermsFailure.unconfirmed;

  Never _throwSessionRequired() =>
      throw BuyerMobileTermsFailure.sessionRequired;
}
