abstract interface class BuyerChangeFeedRepository {
  Stream<BuyerChangeFeedEvent> watch({required String readPermission});
}

final class BuyerChangeFeedEvent {
  const BuyerChangeFeedEvent(this.type);

  final String type;
}
