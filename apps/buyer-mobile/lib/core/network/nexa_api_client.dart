import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

import '../application/evidence_upload_policy.dart';

import 'package:http_parser/http_parser.dart';

import '../application/error/nexa_api_failure.dart';

export '../application/error/nexa_api_failure.dart';

abstract interface class SessionCredentialProvider {
  String? get accessToken;

  int get authorityEpoch;

  String get authorityFingerprint => '$authorityEpoch';

  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  );
}

final class NexaApiResponse {
  const NexaApiResponse({
    required this.body,
    required this.headers,
    required this.data,
  });

  final Map<String, Object?> body;
  final Map<String, String> headers;
  final Object? data;
}

final class NexaApiChangeFeedEvent {
  const NexaApiChangeFeedEvent(this.type);

  final String type;
}

final class NexaApiBinaryResponse {
  NexaApiBinaryResponse({
    required List<int> bytes,
    required this.statusCode,
    required this.headers,
  }) : bytes = Uint8List.fromList(bytes);

  final Uint8List bytes;
  final int statusCode;
  final Map<String, String> headers;

  String get sha256 => sha256Of(bytes);

  void clear() => bytes.fillRange(0, bytes.length, 0);
}

String sha256Of(List<int> bytes) => sha256.convert(bytes).toString();

final class NexaApiOrigin {
  const NexaApiOrigin._(this.uri);

  final Uri uri;

  static NexaApiOrigin parse(String value, {required bool allowLocalHttp}) {
    final parsed = Uri.tryParse(value.trim());
    if (parsed == null ||
        !parsed.hasAuthority ||
        parsed.host.isEmpty ||
        parsed.userInfo.isNotEmpty ||
        (parsed.path.isNotEmpty && parsed.path != '/') ||
        parsed.hasQuery ||
        parsed.hasFragment) {
      throw const FormatException('Configure the API origin only.');
    }

    final localHosts = {'localhost', '127.0.0.1', '::1', '10.0.2.2'};
    final secure = parsed.scheme == 'https';
    final localDevelopment =
        allowLocalHttp &&
        parsed.scheme == 'http' &&
        localHosts.contains(parsed.host.toLowerCase());
    if (!secure && !localDevelopment) {
      throw const FormatException(
        'Use HTTPS, or an approved local HTTP origin in a debug build.',
      );
    }

    return NexaApiOrigin._(
      parsed.replace(path: '', query: null, fragment: null),
    );
  }
}

final class NexaApiClient {
  static const int maxProtectedBinaryBytes = 8 * 1024 * 1024;
  static const int maxEvidenceUploadBytes = evidenceUploadMaximumBytes;

  NexaApiClient({
    required this.origin,
    required this._httpClient,
    this.requestTimeout = const Duration(seconds: 20),
  });

  final NexaApiOrigin origin;
  final http.Client _httpClient;
  final Duration requestTimeout;
  final Map<String, String> _changeFeedCursors = {};
  SessionCredentialProvider? sessionCredentials;

  Future<NexaApiResponse> get(
    String path, {
    Map<String, String> query = const {},
    bool authenticated = true,
    bool refreshAfterUnauthorized = false,
    String? contextTicket,
    String? bearerToken,
  }) => request(
    'GET',
    path,
    query: query,
    authenticated: authenticated,
    refreshAfterUnauthorized: refreshAfterUnauthorized,
    contextTicket: contextTicket,
    bearerToken: bearerToken,
  );

  Stream<NexaApiChangeFeedEvent> watchChangeFeed({
    required int authorityEpoch,
    required String authorityFingerprint,
  }) {
    final session = sessionCredentials;
    if (session == null ||
        session.authorityEpoch != authorityEpoch ||
        session.authorityFingerprint != authorityFingerprint ||
        session.accessToken == null) {
      return const Stream.empty();
    }

    final cursorKey = '$authorityEpoch|$authorityFingerprint';
    return Stream.multi((controller) {
      var cancelled = false;
      var lastEventId = _changeFeedCursors[cursorKey];
      var retryDelay = const Duration(seconds: 1);
      Timer? retryTimer;
      Completer<void>? activeAbort;

      bool hasCurrentAuthority() =>
          session.authorityEpoch == authorityEpoch &&
          session.authorityFingerprint == authorityFingerprint;

      void abortActiveRequest() {
        final abort = activeAbort;
        if (abort != null && !abort.isCompleted) abort.complete();
      }

      void stop() {
        if (cancelled) return;
        cancelled = true;
        retryTimer?.cancel();
        abortActiveRequest();
        unawaited(controller.close());
      }

      late Future<void> Function(
        String? tokenOverride,
        bool retriedAfterUnauthorized,
      )
      connect;

      void retryLater() {
        if (cancelled || !hasCurrentAuthority()) {
          stop();
          return;
        }
        final delay = retryDelay;
        retryDelay = Duration(
          seconds: (retryDelay.inSeconds * 2).clamp(1, 15).toInt(),
        );
        retryTimer?.cancel();
        retryTimer = Timer(delay, () => unawaited(connect(null, false)));
      }

      connect = (tokenOverride, retriedAfterUnauthorized) async {
        if (cancelled) return;
        if (!hasCurrentAuthority()) {
          stop();
          return;
        }
        final token = tokenOverride ?? session.accessToken;
        if (token == null || token.isEmpty) {
          stop();
          return;
        }

        final abort = Completer<void>();
        activeAbort = abort;
        try {
          final headers = <String, String>{
            'Accept': 'text/event-stream',
            'Authorization': 'Bearer $token',
            'Cache-Control': 'no-cache',
          };
          final cursor = lastEventId;
          if (cursor != null) headers['Last-Event-ID'] = cursor;
          final request = http.AbortableRequest(
            'GET',
            origin.uri.resolve('/api/v1/change-feed/stream'),
            abortTrigger: abort.future,
          )..headers.addAll(headers);
          final response = await _httpClient.send(request);
          if (cancelled) return;
          if (!hasCurrentAuthority()) {
            stop();
            return;
          }

          if (response.statusCode == 401) {
            abortActiveRequest();
            if (retriedAfterUnauthorized) {
              stop();
              return;
            }
            try {
              final refreshed = await session.refreshIfCurrent(
                token,
                authorityEpoch,
              );
              if (!hasCurrentAuthority() ||
                  refreshed == null ||
                  refreshed.isEmpty ||
                  refreshed == token) {
                stop();
                return;
              }
              await connect(refreshed, true);
            } catch (_) {
              stop();
            }
            return;
          }

          final contentType = response.headers['content-type']
              ?.split(';')
              .first
              .trim()
              .toLowerCase();
          if (response.statusCode != 200 ||
              contentType != 'text/event-stream') {
            abortActiveRequest();
            if (response.statusCode == 403 || response.statusCode == 404) {
              stop();
            } else {
              retryLater();
            }
            return;
          }

          retryDelay = const Duration(seconds: 1);
          await for (final event in _decodeServerSentEvents(response.stream)) {
            if (cancelled) return;
            if (!hasCurrentAuthority()) {
              stop();
              return;
            }
            if (event.hasId) {
              lastEventId = event.id;
              if (lastEventId == null) {
                _changeFeedCursors.remove(cursorKey);
              } else {
                _changeFeedCursors[cursorKey] = lastEventId!;
              }
            }
            if (event.type == 'resync-required') {
              lastEventId = null;
              _changeFeedCursors.remove(cursorKey);
              controller.add(const NexaApiChangeFeedEvent('resync-required'));
              break;
            }
            if (event.type != 'heartbeat') {
              controller.add(NexaApiChangeFeedEvent(event.type));
            }
          }
          if (!cancelled) retryLater();
        } catch (_) {
          if (!cancelled) retryLater();
        } finally {
          if (!abort.isCompleted) abort.complete();
          if (identical(activeAbort, abort)) activeAbort = null;
        }
      };

      controller.onCancel = () {
        if (cancelled) return;
        cancelled = true;
        retryTimer?.cancel();
        abortActiveRequest();
      };

      unawaited(connect(null, false));
    }, isBroadcast: false);
  }

  Future<NexaApiBinaryResponse> getBinary(
    String path, {
    Map<String, String> query = const {},
    bool refreshAfterUnauthorized = false,
    int maxBytes = maxProtectedBinaryBytes,
  }) async {
    if (maxBytes < 1 || maxBytes > maxProtectedBinaryBytes) {
      throw const NexaApiFailure(
        code: 'invalid_binary_limit',
        userMessage: 'The requested document is not available.',
      );
    }
    final session = sessionCredentials;
    final tokenUsed = session?.accessToken;
    final expectedAuthorityEpoch = session?.authorityEpoch;
    final expectedAuthorityFingerprint = session?.authorityFingerprint;
    if (tokenUsed == null ||
        tokenUsed.isEmpty ||
        session == null ||
        expectedAuthorityEpoch == null) {
      throw const NexaApiFailure(
        code: 'session_required',
        statusCode: 401,
        userMessage: 'Sign in to continue.',
      );
    }

    final first = await _send(
      'GET',
      path,
      query: query,
      body: null,
      bearerToken: tokenUsed,
      contextTicket: null,
      nativeRefreshToken: null,
      acceptHeader: '*/*',
      maxResponseBytes: maxBytes,
    );
    try {
      _requireCurrentAuthority(
        session,
        expectedAuthorityEpoch,
        expectedAuthorityFingerprint,
      );
    } on NexaApiFailure {
      _clearResponseBytes(first);
      rethrow;
    }
    if (first.statusCode == 401 && refreshAfterUnauthorized) {
      final refreshedToken = await session.refreshIfCurrent(
        tokenUsed,
        expectedAuthorityEpoch,
      );
      try {
        _requireCurrentAuthority(
          session,
          expectedAuthorityEpoch,
          expectedAuthorityFingerprint,
        );
      } on NexaApiFailure {
        _clearResponseBytes(first);
        rethrow;
      }
      _clearResponseBytes(first);
      if (refreshedToken == null || refreshedToken == tokenUsed) {
        throw const NexaApiFailure(
          code: 'session_expired',
          statusCode: 401,
          userMessage: 'Your session expired. Sign in again.',
        );
      }
      final retried = await _send(
        'GET',
        path,
        query: query,
        body: null,
        bearerToken: refreshedToken,
        contextTicket: null,
        nativeRefreshToken: null,
        acceptHeader: '*/*',
        maxResponseBytes: maxBytes,
      );
      try {
        _requireCurrentAuthority(
          session,
          expectedAuthorityEpoch,
          expectedAuthorityFingerprint,
        );
      } on NexaApiFailure {
        _clearResponseBytes(retried);
        rethrow;
      }
      _throwForNonSuccess(retried);
      return _binary(retried);
    }
    _throwForNonSuccess(first);
    return _binary(first);
  }

  Future<NexaApiResponse> post(
    String path, {
    Map<String, Object?>? body,
    bool authenticated = true,
    String? contextTicket,
    String? nativeRefreshToken,
    String? bearerToken,
    String? ifMatch,
    String? idempotencyKey,
  }) => request(
    'POST',
    path,
    body: body,
    authenticated: authenticated,
    contextTicket: contextTicket,
    nativeRefreshToken: nativeRefreshToken,
    bearerToken: bearerToken,
    ifMatch: ifMatch,
    idempotencyKey: idempotencyKey,
  );

  Future<NexaApiResponse> postMultipart({
    required String path,
    required Map<String, String> fields,
    required String fieldName,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
    bool refreshAfterUnauthorized = false,
  }) async {
    if (bytes.isEmpty || bytes.length > maxEvidenceUploadBytes) {
      throw const NexaApiFailure(
        code: 'invalid_upload_size',
        statusCode: 400,
        userMessage: 'Choose a file within the supported size limit.',
      );
    }
    final session = sessionCredentials;
    final tokenUsed = session?.accessToken;
    final expectedAuthorityEpoch = session?.authorityEpoch;
    final expectedAuthorityFingerprint = session?.authorityFingerprint;
    if (tokenUsed == null ||
        tokenUsed.isEmpty ||
        session == null ||
        expectedAuthorityEpoch == null) {
      throw const NexaApiFailure(
        code: 'session_required',
        statusCode: 401,
        userMessage: 'Sign in to continue.',
      );
    }

    final first = await _sendMultipart(
      path: path,
      fields: fields,
      fieldName: fieldName,
      filename: filename,
      contentType: contentType,
      bytes: bytes,
      token: tokenUsed,
      idempotencyKey: idempotencyKey,
    );
    try {
      _requireCurrentAuthority(
        session,
        expectedAuthorityEpoch,
        expectedAuthorityFingerprint,
      );
    } on NexaApiFailure {
      _clearResponseBytes(first);
      rethrow;
    }
    if (first.statusCode == 401 && refreshAfterUnauthorized) {
      final refreshedToken = await session.refreshIfCurrent(
        tokenUsed,
        expectedAuthorityEpoch,
      );
      try {
        _requireCurrentAuthority(
          session,
          expectedAuthorityEpoch,
          expectedAuthorityFingerprint,
        );
      } on NexaApiFailure {
        _clearResponseBytes(first);
        rethrow;
      }
      _clearResponseBytes(first);
      if (refreshedToken == null || refreshedToken == tokenUsed) {
        throw const NexaApiFailure(
          code: 'session_expired',
          statusCode: 401,
          userMessage: 'Your session expired. Sign in again.',
        );
      }
      final retried = await _sendMultipart(
        path: path,
        fields: fields,
        fieldName: fieldName,
        filename: filename,
        contentType: contentType,
        bytes: bytes,
        token: refreshedToken,
        idempotencyKey: idempotencyKey,
      );
      try {
        _requireCurrentAuthority(
          session,
          expectedAuthorityEpoch,
          expectedAuthorityFingerprint,
        );
      } on NexaApiFailure {
        _clearResponseBytes(retried);
        rethrow;
      }
      return _decode(retried);
    }
    return _decode(first);
  }

  Future<NexaApiResponse> put(
    String path, {
    Map<String, Object?>? body,
    bool authenticated = true,
    bool refreshAfterUnauthorized = false,
    String? ifMatch,
  }) => request(
    'PUT',
    path,
    body: body,
    authenticated: authenticated,
    refreshAfterUnauthorized: refreshAfterUnauthorized,
    ifMatch: ifMatch,
  );

  Future<NexaApiResponse> request(
    String method,
    String path, {
    Map<String, String> query = const {},
    Map<String, Object?>? body,
    bool authenticated = true,
    bool refreshAfterUnauthorized = false,
    String? contextTicket,
    String? nativeRefreshToken,
    String? bearerToken,
    String? ifMatch,
    String? idempotencyKey,
  }) async {
    final session = sessionCredentials;
    final tokenUsed =
        bearerToken ?? (authenticated ? session?.accessToken : null);
    final expectedAuthorityEpoch = session?.authorityEpoch;
    final expectedAuthorityFingerprint = session?.authorityFingerprint;
    if (authenticated && (tokenUsed == null || tokenUsed.isEmpty)) {
      throw const NexaApiFailure(
        code: 'session_required',
        statusCode: 401,
        userMessage: 'Sign in to continue.',
      );
    }
    final authorities = [
      tokenUsed != null,
      contextTicket != null,
      nativeRefreshToken != null,
    ].where((present) => present).length;
    if (authorities > 1) {
      throw const NexaApiFailure(
        code: 'invalid_authority_combination',
        statusCode: 400,
        userMessage: 'The access context could not be verified.',
      );
    }

    final first = await _send(
      method,
      path,
      query: query,
      body: body,
      bearerToken: tokenUsed,
      contextTicket: contextTicket,
      nativeRefreshToken: nativeRefreshToken,
      ifMatch: ifMatch,
      idempotencyKey: idempotencyKey,
    );
    if (first.statusCode == 401 &&
        authenticated &&
        refreshAfterUnauthorized &&
        session != null &&
        tokenUsed != null &&
        expectedAuthorityEpoch != null) {
      final refreshedToken = await session.refreshIfCurrent(
        tokenUsed,
        expectedAuthorityEpoch,
      );
      if (refreshedToken == null || refreshedToken == tokenUsed) {
        throw const NexaApiFailure(
          code: 'session_expired',
          statusCode: 401,
          userMessage: 'Your session expired. Sign in again.',
        );
      }
      if (session.authorityEpoch != expectedAuthorityEpoch ||
          session.authorityFingerprint != expectedAuthorityFingerprint) {
        throw const NexaApiFailure(
          code: 'authority_changed',
          statusCode: 403,
          userMessage: 'Your access changed. Refresh before continuing.',
        );
      }
      final retried = await _send(
        method,
        path,
        query: query,
        body: body,
        bearerToken: refreshedToken,
        contextTicket: null,
        nativeRefreshToken: null,
        ifMatch: ifMatch,
        idempotencyKey: idempotencyKey,
      );
      return _decode(retried);
    }
    return _decode(first);
  }

  Future<http.Response> _send(
    String method,
    String path, {
    required Map<String, String> query,
    required Map<String, Object?>? body,
    required String? bearerToken,
    required String? contextTicket,
    required String? nativeRefreshToken,
    String? ifMatch,
    String? idempotencyKey,
    String acceptHeader = 'application/json, application/problem+json',
    int? maxResponseBytes,
  }) async {
    final request = http.Request(method, _endpoint(path, query));
    request.followRedirects = false;
    request.headers.addAll({
      'Accept': acceptHeader,
      'X-Nexa-Surface': 'PORTAL',
      'X-Nexa-Client': 'NATIVE',
    });
    if (bearerToken != null) {
      request.headers['Authorization'] = 'Bearer $bearerToken';
    }
    if (contextTicket != null) {
      request.headers['X-Nexa-Context-Ticket'] = contextTicket;
    }
    if (nativeRefreshToken != null) {
      request.headers['X-Nexa-Refresh-Token'] = nativeRefreshToken;
    }
    if (ifMatch != null) request.headers['If-Match'] = ifMatch;
    if (idempotencyKey != null) {
      request.headers['Idempotency-Key'] = idempotencyKey;
    }
    if (body != null) {
      request.headers['Content-Type'] = 'application/json; charset=UTF-8';
      request.body = jsonEncode(body);
    }

    try {
      final streamed = await _httpClient.send(request).timeout(requestTimeout);
      if (maxResponseBytes != null) {
        final announcedLength = streamed.contentLength;
        if (announcedLength != null && announcedLength > maxResponseBytes) {
          await streamed.stream.listen((_) {}).cancel();
          throw const NexaApiFailure(
            code: 'response_too_large',
            userMessage: 'This document exceeds the protected download limit.',
          );
        }
        final bytes = BytesBuilder(copy: false);
        var received = 0;
        await for (final chunk in streamed.stream.timeout(requestTimeout)) {
          if (chunk.length > maxResponseBytes - received) {
            throw const NexaApiFailure(
              code: 'response_too_large',
              userMessage:
                  'This document exceeds the protected download limit.',
            );
          }
          bytes.add(chunk);
          received += chunk.length;
        }
        return http.Response.bytes(
          bytes.takeBytes(),
          streamed.statusCode,
          request: request,
          headers: streamed.headers,
          isRedirect: streamed.isRedirect,
          persistentConnection: streamed.persistentConnection,
          reasonPhrase: streamed.reasonPhrase,
        );
      }
      return await http.Response.fromStream(streamed).timeout(requestTimeout);
    } on TimeoutException {
      throw const NexaApiFailure(
        code: 'network_timeout',
        userMessage:
            'Nexa did not respond. Check your connection and try again.',
        retryable: true,
      );
    } on http.ClientException {
      throw const NexaApiFailure(
        code: 'network_unavailable',
        userMessage:
            'Nexa is unavailable. Check your connection and try again.',
        retryable: true,
      );
    }
  }

  Future<http.Response> _sendMultipart({
    required String path,
    required Map<String, String> fields,
    required String fieldName,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String token,
    required String idempotencyKey,
  }) async {
    final request = http.MultipartRequest('POST', _endpoint(path, const {}));
    request.followRedirects = false;
    request.headers.addAll({
      'Accept': 'application/json, application/problem+json',
      'Authorization': 'Bearer $token',
      'X-Nexa-Surface': 'PORTAL',
      'X-Nexa-Client': 'NATIVE',
      'Idempotency-Key': idempotencyKey,
    });
    request.fields.addAll(fields);
    request.files.add(
      http.MultipartFile.fromBytes(
        fieldName,
        bytes,
        filename: filename,
        contentType: MediaType.parse(contentType),
      ),
    );

    try {
      final streamed = await _httpClient.send(request).timeout(requestTimeout);
      return await http.Response.fromStream(streamed).timeout(requestTimeout);
    } on TimeoutException {
      throw const NexaApiFailure(
        code: 'network_timeout',
        userMessage:
            'Nexa did not respond. Check your connection and try again.',
        retryable: true,
      );
    } on http.ClientException {
      throw const NexaApiFailure(
        code: 'network_unavailable',
        userMessage:
            'Nexa is unavailable. Check your connection and try again.',
        retryable: true,
      );
    }
  }

  Uri _endpoint(String path, Map<String, String> query) {
    final normalized = path.startsWith('/') ? path.substring(1) : path;
    final segments = normalized.split('/');
    if (normalized.isEmpty ||
        normalized.contains('..') ||
        normalized.contains('?') ||
        normalized.contains('#') ||
        normalized.contains('\\') ||
        segments.any((segment) => segment.isEmpty)) {
      throw const NexaApiFailure(
        code: 'invalid_api_path',
        userMessage: 'The requested service route is unavailable.',
      );
    }
    return origin.uri.replace(
      path: '/api/v1/$normalized',
      queryParameters: query.isEmpty ? null : query,
    );
  }

  NexaApiResponse _decode(http.Response response) {
    final decoded = _decodeJson(response.body);
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (response.body.trim().isEmpty) {
        return NexaApiResponse(
          body: const {},
          headers: response.headers,
          data: const {},
        );
      }
      if (decoded == null) {
        throw const NexaApiFailure(
          code: 'invalid_response',
          userMessage: 'Nexa returned information that could not be read.',
        );
      }
      final object = decoded is Map<String, dynamic>
          ? Map<String, Object?>.from(decoded)
          : null;
      if (object == null && decoded is! List) {
        throw const NexaApiFailure(
          code: 'invalid_response',
          userMessage: 'Nexa returned information that could not be read.',
        );
      }
      return NexaApiResponse(
        body: object ?? const {},
        headers: response.headers,
        data: decoded,
      );
    }

    final problem = decoded is Map<String, dynamic>
        ? Map<String, Object?>.from(decoded)
        : const <String, Object?>{};
    final code = _string(problem['code']) ?? 'http_${response.statusCode}';
    final correlationId = _string(problem['correlationId']);
    throw NexaApiFailure(
      code: code,
      statusCode: response.statusCode,
      correlationId: correlationId,
      retryable: problem['retryable'] == true || response.statusCode >= 500,
      userMessage: switch (response.statusCode) {
        400 => 'Check the information and try again.',
        401 => 'Your session expired. Sign in again.',
        403 => 'This information is not available for your current access.',
        404 => 'This information is no longer available.',
        409 || 412 => 'This information changed. Refresh and try again.',
        >= 500 => 'Nexa is temporarily unavailable. Try again later.',
        _ => 'Nexa could not complete the request.',
      },
    );
  }

  void _throwForNonSuccess(http.Response response) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      try {
        _decode(response);
      } finally {
        _clearResponseBytes(response);
      }
    }
  }

  void _requireCurrentAuthority(
    SessionCredentialProvider session,
    int? expectedAuthorityEpoch,
    String? expectedAuthorityFingerprint,
  ) {
    if (session.authorityEpoch != expectedAuthorityEpoch ||
        session.authorityFingerprint != expectedAuthorityFingerprint) {
      throw const NexaApiFailure(
        code: 'authority_changed',
        statusCode: 403,
        userMessage: 'Your access changed. Refresh before continuing.',
      );
    }
  }

  void _clearResponseBytes(http.Response response) {
    final bytes = response.bodyBytes;
    bytes.fillRange(0, bytes.length, 0);
  }

  NexaApiBinaryResponse _binary(http.Response response) =>
      _binaryAndClearResponse(response);

  NexaApiBinaryResponse _binaryAndClearResponse(http.Response response) {
    final binary = NexaApiBinaryResponse(
      bytes: response.bodyBytes,
      statusCode: response.statusCode,
      headers: response.headers,
    );
    _clearResponseBytes(response);
    return binary;
  }

  Object? _decodeJson(String value) {
    if (value.trim().isEmpty) return const {};
    try {
      final json = jsonDecode(value);
      if (json is Map<String, dynamic> || json is List) return json;
    } on FormatException {
      return null;
    }
    return null;
  }

  String? _string(Object? value) =>
      value is String && value.isNotEmpty ? value : null;

  void close() => _httpClient.close();
}

Stream<_ServerSentEvent> _decodeServerSentEvents(
  Stream<List<int>> source,
) async* {
  var type = 'message';
  String? id;
  var hasId = false;
  var hasData = false;

  await for (final line
      in source.transform(utf8.decoder).transform(const LineSplitter())) {
    if (line.isEmpty) {
      if (hasData) {
        yield _ServerSentEvent(type: type, id: id, hasId: hasId);
      }
      type = 'message';
      id = null;
      hasId = false;
      hasData = false;
      continue;
    }
    if (line.startsWith(':')) continue;

    final separator = line.indexOf(':');
    final field = separator < 0 ? line : line.substring(0, separator);
    var value = separator < 0 ? '' : line.substring(separator + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    switch (field) {
      case 'event':
        type = value.isEmpty ? 'message' : value;
      case 'id':
        if (!value.contains('\u0000')) {
          id = value.isEmpty || RegExp(r'^\d+$').hasMatch(value)
              ? value.isEmpty
                    ? null
                    : value
              : null;
          hasId = true;
        }
      case 'data':
        hasData = true;
    }
  }

  if (hasData) yield _ServerSentEvent(type: type, id: id, hasId: hasId);
}

final class _ServerSentEvent {
  const _ServerSentEvent({
    required this.type,
    required this.id,
    required this.hasId,
  });

  final String type;
  final String? id;
  final bool hasId;
}
