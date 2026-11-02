import '../domain/model/buyer_profile.dart';
export '../domain/model/buyer_profile.dart';

abstract interface class BuyerProfileRepository {
  Future<BuyerProfile> load();
  Future<BuyerProfile> update(BuyerProfile current, BuyerProfileEdit edit);
}
