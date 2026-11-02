final class BuyerAddress {
  BuyerAddress({
    required this.id,
    required this.label,
    required this.version,
    required this.isDefault,
    required Map<String, Object?> details,
  }) : details = Map.unmodifiable(details);
  final String id, label;
  final int version;
  final bool isDefault;
  final Map<String, Object?> details;
  String text(String key) =>
      details[key] is String ? details[key] as String : '';
}

final class BuyerAddressEdit {
  BuyerAddressEdit({required this.label, required Map<String, Object?> details})
    : details = Map.unmodifiable(details);
  final String label;
  final Map<String, Object?> details;
}

final class BuyerAddressOption {
  const BuyerAddressOption(this.code, this.label);
  final String code, label;
}
