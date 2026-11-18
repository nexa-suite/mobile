import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

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
    final viewModel = context.watch<BuyerMobileTermsViewModel>();
    final document = viewModel.document;
    return Scaffold(
      appBar: AppBar(title: const Text('Términos de uso')),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            const NexaPageIntro(
              title: 'Términos de uso',
              description: 'Consulta la versión vigente de los términos.',
              icon: Icons.description_outlined,
            ),
            if (viewModel.message != null) ...[
              NexaNotice(viewModel.message!),
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
                        'Versión ${document.version}',
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
              _acceptanceControls(viewModel),
            if (viewModel.status == BuyerMobileTermsViewState.accepted)
              Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const NexaNotice(
                    'Ya aceptaste la versión vigente de estos términos.',
                  ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: widget.onContinue,
                    child: const Text('Continuar a Nexa Buyer'),
                  ),
                ],
              ),
            if (viewModel.status == BuyerMobileTermsViewState.publicOnly)
              const NexaNotice(
                'Para aceptar los términos, inicia sesión con tu acceso de comprador.',
              ),
            if (viewModel.status == BuyerMobileTermsViewState.unavailable ||
                viewModel.status == BuyerMobileTermsViewState.idle)
              OutlinedButton.icon(
                onPressed: viewModel.isLoading ? null : viewModel.refresh,
                icon: const Icon(Icons.refresh),
                label: const Text('Actualizar términos y estado'),
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
                label: const Text('Cerrar sesión'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _acceptanceControls(BuyerMobileTermsViewModel viewModel) => Column(
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
        title: const Text(
          'He leído y acepto los Términos de uso de Nexa Mobile.',
        ),
      ),
      const SizedBox(height: 8),
      FilledButton(
        key: const Key('buyer-mobile-terms-continue'),
        onPressed: _acceptedTerms && viewModel.canAccept
            ? viewModel.acceptCurrentTerms
            : null,
        child: Text(
          viewModel.status == BuyerMobileTermsViewState.submitting
              ? 'Enviando…'
              : 'Continuar',
        ),
      ),
    ],
  );
}
