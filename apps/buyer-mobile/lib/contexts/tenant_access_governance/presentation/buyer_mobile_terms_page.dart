import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../../l10n/generated/app_localizations.dart';

import '../../../core/presentation/nexa_buyer_components.dart';
import 'buyer_mobile_terms_view_model.dart';

final class BuyerMobileTermsPage extends StatefulWidget {
  const BuyerMobileTermsPage({super.key, this.onContinue, this.onSignOut});

  final VoidCallback? onContinue;
  final VoidCallback? onSignOut;

  @override
  State<BuyerMobileTermsPage> createState() => _BuyerMobileTermsPageState();
}

final class _BuyerMobileTermsPageState extends State<BuyerMobileTermsPage> {
  BuyerMobileTermsViewModel? _viewModel;
  String? _consentRevision;
  bool _acceptedTerms = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final nextViewModel = context.read<BuyerMobileTermsViewModel>();
    if (!identical(nextViewModel, _viewModel)) {
      _viewModel?.removeListener(_syncConsentRevision);
      _viewModel = nextViewModel..addListener(_syncConsentRevision);
      _syncConsentRevision();
      if (nextViewModel.status == BuyerMobileTermsViewState.idle) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted &&
              identical(_viewModel, nextViewModel) &&
              nextViewModel.status == BuyerMobileTermsViewState.idle) {
            nextViewModel.refresh();
          }
        });
      }
    }
  }

  @override
  void dispose() {
    _viewModel?.removeListener(_syncConsentRevision);
    super.dispose();
  }

  void _syncConsentRevision() {
    final nextRevision = _viewModel?.consentRevision;
    if (nextRevision == _consentRevision) return;
    _consentRevision = nextRevision;
    if (_acceptedTerms && mounted) {
      setState(() => _acceptedTerms = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = AppLocalizations.of(context)!;
    final viewModel = context.watch<BuyerMobileTermsViewModel>();
    final document = viewModel.document;
    return Scaffold(
      appBar: AppBar(title: Text(strings.termsTitle)),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            NexaPageIntro(
              title: strings.termsTitle,
              description: strings.termsDescription,
              icon: Icons.description_outlined,
            ),
            if (viewModel.notice case final notice?) ...[
              NexaNotice(_noticeText(strings, notice)),
              const SizedBox(height: 12),
            ],
            if (document != null) ...[
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        strings.termsVersion(document.version),
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                      const SizedBox(height: 12),
                      SelectableText(document.content),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
            ] else if (viewModel.isLoading) ...[
              const Padding(
                padding: EdgeInsets.all(36),
                child: Center(child: CircularProgressIndicator()),
              ),
            ],
            if (viewModel.status ==
                BuyerMobileTermsViewState.requiresAcceptance)
              _acceptanceControls(viewModel, strings),
            if (viewModel.status == BuyerMobileTermsViewState.accepted)
              Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  NexaNotice(strings.termsAccepted),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: widget.onContinue,
                    child: Text(strings.termsContinue),
                  ),
                ],
              ),
            if (viewModel.status == BuyerMobileTermsViewState.publicOnly)
              NexaNotice(strings.termsSignIn),
            if (viewModel.status == BuyerMobileTermsViewState.unavailable ||
                viewModel.status == BuyerMobileTermsViewState.idle)
              OutlinedButton.icon(
                onPressed: viewModel.isLoading ? null : viewModel.refresh,
                icon: const Icon(Icons.refresh),
                label: Text(strings.termsRefresh),
              ),
            if (viewModel.isLoading && document != null)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Center(child: CircularProgressIndicator()),
              ),
            if (viewModel.hasActiveBuyerSession &&
                !viewModel.isAcceptedForCurrentSession)
              TextButton.icon(
                onPressed: widget.onSignOut,
                icon: const Icon(Icons.logout),
                label: Text(strings.signOut),
              ),
          ],
        ),
      ),
    );
  }

  String _noticeText(AppLocalizations strings, BuyerMobileTermsNotice notice) =>
      switch (notice) {
        BuyerMobileTermsNotice.refreshKeptAcceptance =>
          strings.termsRefreshKeptAcceptance,
        BuyerMobileTermsNotice.acceptanceUnconfirmed =>
          strings.termsAcceptanceUnconfirmed,
        BuyerMobileTermsNotice.acceptanceAccessDenied =>
          strings.termsAcceptanceAccessDenied,
        BuyerMobileTermsNotice.documentChanged => strings.termsDocumentChanged,
        BuyerMobileTermsNotice.checkingAcceptance =>
          strings.termsCheckingAcceptance,
        BuyerMobileTermsNotice.newVersion => strings.termsNewVersion,
        BuyerMobileTermsNotice.acceptanceNotRecorded =>
          strings.termsAcceptanceNotRecorded,
        BuyerMobileTermsNotice.cannotConfirmResult =>
          strings.termsCannotConfirmResult,
        BuyerMobileTermsNotice.statusAccessDenied =>
          strings.termsStatusAccessDenied,
        BuyerMobileTermsNotice.termsUnavailable =>
          strings.termsTermsUnavailable,
      };

  Widget _acceptanceControls(
    BuyerMobileTermsViewModel viewModel,
    AppLocalizations strings,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      CheckboxListTile(
        key: const Key('buyer-mobile-terms-checkbox'),
        value: _acceptedTerms,
        onChanged: viewModel.isLoading
            ? null
            : (accepted) => setState(() => _acceptedTerms = accepted ?? false),
        controlAffinity: ListTileControlAffinity.leading,
        contentPadding: EdgeInsets.zero,
        title: Text(strings.termsAcceptance),
      ),
      const SizedBox(height: 8),
      FilledButton(
        key: const Key('buyer-mobile-terms-continue'),
        onPressed: _acceptedTerms && viewModel.canAccept
            ? viewModel.acceptCurrentTerms
            : null,
        child: Text(
          viewModel.status == BuyerMobileTermsViewState.submitting
              ? strings.termsSubmitting
              : strings.continueLabel,
        ),
      ),
    ],
  );
}
