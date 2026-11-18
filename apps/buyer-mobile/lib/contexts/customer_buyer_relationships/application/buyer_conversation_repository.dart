final class BuyerMessage {
  const BuyerMessage({
    required this.id,
    required this.body,
    required this.senderKind,
    required this.createdAt,
  });
  final String id;
  final String body;
  final String senderKind;
  final DateTime createdAt;
}

final class BuyerMessagePage {
  const BuyerMessagePage(this.items, this.nextCursor, this.hasMore);
  final List<BuyerMessage> items;
  final String? nextCursor;
  final bool hasMore;
}

abstract interface class BuyerConversationRepository {
  Future<BuyerMessagePage> list(String? cursor);
  Future<BuyerMessage> send(String body, String key);
}

final class PendingBuyerMessage {
  const PendingBuyerMessage(this.body, this.key);
  final String body;
  final String key;
}

abstract interface class PendingBuyerMessageStore {
  Future<PendingBuyerMessage?> read(String scope);
  Future<void> write(String scope, PendingBuyerMessage message);
  Future<void> clear(String scope);
}
