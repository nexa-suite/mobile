import '../l10n/generated/app_localizations.dart';
import 'buyer_workspace_navigation.dart';

extension BuyerWorkspaceLabels on BuyerWorkspaceDestination {
  String localizedLabel(AppLocalizations strings) => switch (this) {
    BuyerWorkspaceDestination.home => strings.homeTab,
    BuyerWorkspaceDestination.catalog => strings.catalogTab,
    BuyerWorkspaceDestination.orders => strings.ordersTab,
    BuyerWorkspaceDestination.credit => strings.creditTab,
    BuyerWorkspaceDestination.documents => strings.documentsTab,
    BuyerWorkspaceDestination.wallet => strings.walletTab,
    BuyerWorkspaceDestination.deliveries => strings.deliveriesTab,
    BuyerWorkspaceDestination.requests => strings.requestsTab,
  };
}
