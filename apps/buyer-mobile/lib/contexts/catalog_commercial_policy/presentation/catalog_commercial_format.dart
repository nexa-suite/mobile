import '../../../core/presentation/nexa_display_format.dart';

String buyerCatalogAmountLabel(String rawAmount) => nexaAmountLabel(rawAmount);

String buyerCatalogPriceLabel(String? amount, String? currency) =>
    nexaMoneyLabel(amount, currency);

String buyerCatalogProductCountLabel(int count) =>
    '$count ${count == 1 ? 'producto' : 'productos'}';

String buyerCatalogUnitLabel(String? unit, {num? quantity}) {
  final code = unit?.trim() ?? '';
  if (code.isEmpty) return '';
  if (code.toUpperCase() == 'UNIT') {
    return quantity == 1 ? 'unidad' : 'unidades';
  }
  return code;
}

String buyerCatalogQuantityLabel(num quantity, String? unit) {
  final formattedQuantity = buyerCatalogAmountLabel(quantity.toString());
  final formattedUnit = buyerCatalogUnitLabel(unit, quantity: quantity);
  return [
    formattedQuantity,
    formattedUnit,
  ].where((value) => value.isNotEmpty).join(' ');
}

String buyerCatalogCategoryLabel(String name) => switch (name.toLowerCase()) {
  'butter' => 'Mantequilla',
  'charcuterie' => 'Charcutería',
  'cheese' => 'Quesos',
  'dairy' => 'Lácteos',
  'dessert' => 'Postres',
  _ => name,
};
