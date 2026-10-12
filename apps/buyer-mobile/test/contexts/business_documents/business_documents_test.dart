import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:nexa_buyer_mobile/contexts/business_documents/application/business_documents_repository.dart';
import 'package:nexa_buyer_mobile/contexts/business_documents/infrastructure/business_documents_repository_impl.dart';
import 'package:nexa_buyer_mobile/contexts/business_documents/presentation/business_documents_view_model.dart';
import 'package:nexa_buyer_mobile/contexts/tenant_access_governance/application/buyer_access_repository.dart';
import 'package:nexa_buyer_mobile/core/network/nexa_api_client.dart';

const _documentId = '00000000-0000-4000-8000-000000000001';
const _accountId = '00000000-0000-4000-8000-000000000002';
const _subjectId = '00000000-0000-4000-8000-000000000003';
const _body = [37, 80, 68, 70, 45, 49, 46, 55];

void main() {
  test(
    'a binary response is verified against GENERATED document metadata',
    () async {
      http.Request? captured;
      final credentials = _FakeCredentials();
      final api = NexaApiClient(
        origin: NexaApiOrigin.parse(
          'https://api.nexa.example',
          allowLocalHttp: false,
        ),
        httpClient: MockClient((request) async {
          captured = request;
          return http.Response.bytes(
            _body,
            200,
            headers: {
              'content-type': 'application/pdf',
              'x-content-sha256': sha256Of(_body),
              'cache-control': 'private, no-store',
            },
          );
        }),
      )..sessionCredentials = credentials;
      final repository = BusinessDocumentsRepositoryImpl(api);

      final content = await repository.download(_generatedDocument());

      expect(content.document.status, 'GENERATED');
      expect(content.bytes, _body);
      expect(content.sha256, sha256Of(_body));
      expect(
        captured!.url.path,
        '/api/v1/business-documents/$_documentId/downloads',
      );
      expect(captured!.headers['accept'], '*/*');
      expect(captured!.headers['authorization'], 'Bearer access-secret');
      expect(captured!.headers['x-nexa-client'], 'NATIVE');
      expect(captured!.headers['x-nexa-surface'], 'PORTAL');

      content.clear();
      expect(content.bytes, everyElement(0));
      api.close();
    },
  );

  test('a 403 download denial preserves the signed-in authority', () async {
    final access = _FakeAccess(_signedInSnapshot());
    final api = NexaApiClient(
      origin: NexaApiOrigin.parse(
        'https://api.nexa.example',
        allowLocalHttp: false,
      ),
      httpClient: MockClient((request) async {
        if (request.url.path.endsWith('/downloads')) {
          return http.Response(
            '{"code":"DOCUMENT_DOWNLOAD_DENIED","detail":"private"}',
            403,
            headers: {'content-type': 'application/problem+json'},
          );
        }
        return http.Response(_documentJson(), 200);
      }),
    )..sessionCredentials = _FakeCredentials();
    final viewModel = BusinessDocumentDetailViewModel(
      BusinessDocumentsRepositoryImpl(api),
      access,
      _documentId,
    );

    await viewModel.load();
    expect(viewModel.status, BusinessDocumentDetailStatus.current);
    await viewModel.download();

    expect(
      viewModel.downloadStatus,
      BusinessDocumentDownloadStatus.permissionDenied,
    );
    expect(access.snapshot.isSignedIn, isTrue);
    expect(access.invalidations, 0);

    viewModel.dispose();
    api.close();
    await access.dispose();
  });

  test(
    'context change clears existing and stale-completion document bytes',
    () async {
      final access = _FakeAccess(_signedInSnapshot());
      final pendingContent = Completer<BusinessDocumentContentProjection>();
      final repository = _ControlledRepository(pendingContent);
      final viewModel = BusinessDocumentDetailViewModel(
        repository,
        access,
        _documentId,
      );

      await viewModel.load();
      final loading = viewModel.download();
      expect(viewModel.downloadStatus, BusinessDocumentDownloadStatus.loading);

      access.emit(_signedInSnapshot(epoch: 2, tenantId: 'tenant-new'));
      expect(viewModel.document, isNull);
      expect(viewModel.content, isNull);

      final staleContent = BusinessDocumentContentProjection(
        document: _generatedDocument(),
        bytes: _body,
        sha256: sha256Of(_body),
      );
      pendingContent.complete(staleContent);
      await loading;

      expect(viewModel.content, isNull);
      expect(viewModel.downloadStatus, BusinessDocumentDownloadStatus.idle);
      expect(staleContent.bytes, everyElement(0));

      viewModel.dispose();
      await access.dispose();
    },
  );
}

BusinessDocumentProjection _generatedDocument() => BusinessDocumentProjection(
  id: _documentId,
  clientAccountId: _accountId,
  subjectType: 'SALES_ORDER',
  subjectId: _subjectId,
  documentType: 'INVOICE',
  documentNumber: 'INV-2026-01',
  version: 1,
  status: 'GENERATED',
  format: 'PDF',
  contentType: 'application/pdf',
  checksumSha256: sha256Of(_body),
  byteSize: _body.length,
  createdAt: DateTime.utc(2026, 10, 9),
  updatedAt: DateTime.utc(2026, 10, 9),
  generatedAt: DateTime.utc(2026, 10, 9),
);

String _documentJson() =>
    '{'
    '"id":"$_documentId",'
    '"clientAccountId":"$_accountId",'
    '"subjectType":"SALES_ORDER",'
    '"subjectId":"$_subjectId",'
    '"documentType":"INVOICE",'
    '"documentNumber":"INV-2026-01",'
    '"version":1,'
    '"status":"GENERATED",'
    '"format":"PDF",'
    '"contentType":"application/pdf",'
    '"checksumSha256":"${sha256Of(_body)}",'
    '"byteSize":${_body.length},'
    '"createdAt":"2026-10-09T12:00:00Z",'
    '"updatedAt":"2026-10-09T12:00:00Z",'
    '"generatedAt":"2026-10-09T12:00:00Z"'
    '}';

BuyerAccessSnapshot _signedInSnapshot({
  int epoch = 1,
  String tenantId = 'tenant-1',
}) => BuyerAccessSnapshot(
  status: BuyerAccessStatus.signedIn,
  authorityEpoch: epoch,
  currentContext: BuyerAccessContext(
    membershipId: 'membership-1',
    tenantId: tenantId,
    tenantName: 'Company',
    tenantSlug: 'company',
    workspaceId: 'workspace-1',
    workspaceName: 'Main',
    workspaceSlug: 'main',
    roles: const {'BUYER'},
    permissions: const {'document.read', 'document.download'},
    authorizationVersion: 7,
  ),
);

final class _FakeCredentials implements SessionCredentialProvider {
  @override
  String? get accessToken => 'access-secret';

  @override
  int get authorityEpoch => 1;

  @override
  String get authorityFingerprint => 'membership-1|tenant-1|workspace-1|7';

  @override
  Future<String?> refreshIfCurrent(
    String tokenUsed,
    int expectedAuthorityEpoch,
  ) async => null;
}

final class _ControlledRepository implements BusinessDocumentsRepository {
  _ControlledRepository(this._content);

  final Completer<BusinessDocumentContentProjection> _content;

  @override
  Future<BusinessDocumentsPageProjection> list({required int page}) =>
      throw UnimplementedError();

  @override
  Future<BusinessDocumentProjection> detail(String documentId) async =>
      _generatedDocument();

  @override
  Future<BusinessDocumentContentProjection> download(
    BusinessDocumentProjection document,
  ) => _content.future;
}

final class _FakeAccess implements BuyerAccessRepository {
  _FakeAccess(this._snapshot);

  BuyerAccessSnapshot _snapshot;
  final _changes = StreamController<BuyerAccessSnapshot>.broadcast(sync: true);
  int invalidations = 0;

  @override
  BuyerAccessSnapshot get snapshot => _snapshot;

  @override
  Stream<BuyerAccessSnapshot> get changes => _changes.stream;

  void emit(BuyerAccessSnapshot snapshot) {
    _snapshot = snapshot;
    _changes.add(snapshot);
  }

  @override
  Future<void> signIn({required String identifier, required String password}) =>
      throw UnimplementedError();

  @override
  Future<void> selectContext(String membershipId) => throw UnimplementedError();

  @override
  Future<void> signOut() => throw UnimplementedError();

  @override
  void invalidateLocalSession() => invalidations++;

  @override
  Future<void> dispose() => _changes.close();
}
