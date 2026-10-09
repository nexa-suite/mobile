import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'business_documents_view_model.dart';

final class BusinessDocumentsPage extends StatefulWidget {
  const BusinessDocumentsPage({super.key});

  @override
  State<BusinessDocumentsPage> createState() => _BusinessDocumentsPageState();
}

final class _BusinessDocumentsPageState extends State<BusinessDocumentsPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<BusinessDocumentsViewModel>().refresh();
    });
  }

  @override
  Widget build(BuildContext context) {
    final viewModel = context.watch<BusinessDocumentsViewModel>();
    return switch (viewModel.status) {
      BusinessDocumentsStatus.idle || BusinessDocumentsStatus.loading =>
        const Center(child: CircularProgressIndicator()),
      BusinessDocumentsStatus.permissionDenied => _DocumentMessage(
        message: viewModel.message ?? 'No tienes permiso para ver documentos.',
        icon: Icons.lock_outline,
      ),
      BusinessDocumentsStatus.unavailable => _DocumentMessage(
        message:
            viewModel.message ?? 'No se pudieron consultar los documentos.',
        icon: Icons.cloud_off_outlined,
        action: TextButton.icon(
          onPressed: viewModel.refresh,
          icon: const Icon(Icons.refresh),
          label: const Text('Reintentar'),
        ),
      ),
      BusinessDocumentsStatus.current when viewModel.items.isEmpty =>
        _DocumentMessage(
          message: 'No hay documentos disponibles para este contexto.',
          icon: Icons.description_outlined,
          action: TextButton.icon(
            onPressed: viewModel.refresh,
            icon: const Icon(Icons.refresh),
            label: const Text('Actualizar'),
          ),
        ),
      BusinessDocumentsStatus.current => _DocumentList(viewModel: viewModel),
    };
  }
}

final class _DocumentList extends StatelessWidget {
  const _DocumentList({required this.viewModel});

  final BusinessDocumentsViewModel viewModel;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Expanded(
        child: RefreshIndicator(
          onRefresh: viewModel.refresh,
          child: ListView.separated(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.symmetric(vertical: 8),
            itemCount: viewModel.items.length,
            separatorBuilder: (context, index) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final document = viewModel.items[index];
              return ListTile(
                leading: const CircleAvatar(
                  child: Icon(Icons.description_outlined),
                ),
                title: Text(
                  document.documentNumber ?? document.documentType,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  '${document.documentType} · ${document.status} · ${document.format}',
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/documents/${document.id}'),
              );
            },
          ),
        ),
      ),
      _DocumentPagination(viewModel: viewModel),
    ],
  );
}

final class _DocumentPagination extends StatelessWidget {
  const _DocumentPagination({required this.viewModel});

  final BusinessDocumentsViewModel viewModel;

  @override
  Widget build(BuildContext context) {
    final hasPrevious = viewModel.page > 0;
    final hasNext =
        (viewModel.page + 1) * BusinessDocumentsViewModel.pageSize <
        viewModel.total;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text('Página ${viewModel.page + 1} · ${viewModel.total} documentos'),
          Row(
            children: [
              IconButton(
                tooltip: 'Página anterior',
                onPressed: hasPrevious ? viewModel.previousPage : null,
                icon: const Icon(Icons.chevron_left),
              ),
              IconButton(
                tooltip: 'Página siguiente',
                onPressed: hasNext ? viewModel.nextPage : null,
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

final class _DocumentMessage extends StatelessWidget {
  const _DocumentMessage({
    required this.message,
    required this.icon,
    this.action,
  });

  final String message;
  final IconData icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 42),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          if (action != null) ...[const SizedBox(height: 8), action!],
        ],
      ),
    ),
  );
}
