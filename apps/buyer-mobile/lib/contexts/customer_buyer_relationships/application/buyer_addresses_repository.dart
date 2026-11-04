import '../domain/model/buyer_address.dart';
export '../domain/model/buyer_address.dart';

abstract interface class BuyerAddressesRepository {
  Future<List<BuyerAddress>> list();
  Future<BuyerAddress> save(BuyerAddressEdit edit, {BuyerAddress? current});
  Future<void> makeDefault(BuyerAddress address);
  Future<void> remove(BuyerAddress address);
  Future<List<BuyerAddressOption>> options(String resource, {String? parent});
}
