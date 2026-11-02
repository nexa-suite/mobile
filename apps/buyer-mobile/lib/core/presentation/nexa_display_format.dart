String nexaDateLabel(String? value, {String pending = 'Por confirmar'}) {
  final date = value == null ? null : DateTime.tryParse(value);
  if (date == null) return pending;
  return '${date.day.toString().padLeft(2, '0')}/'
      '${date.month.toString().padLeft(2, '0')}/${date.year}';
}

String nexaAmountLabel(String rawAmount) {
  final match = RegExp(r'^(-?)(\d+)(?:\.(\d+))?$').firstMatch(rawAmount.trim());
  if (match == null) return rawAmount;

  final sign = match.group(1) ?? '';
  final digits = match.group(2)!;
  final grouped = StringBuffer();
  for (var index = 0; index < digits.length; index++) {
    if (index > 0 && (digits.length - index) % 3 == 0) grouped.write('.');
    grouped.write(digits[index]);
  }

  final fraction = (match.group(3) ?? '').replaceFirst(RegExp(r'0+$'), '');
  return '$sign$grouped${fraction.isEmpty ? '' : ',$fraction'}';
}

String nexaMoneyLabel(String? amount, String? currency) {
  if (amount == null || amount.isEmpty) return 'Precio no disponible';
  final code = currency?.trim().toUpperCase() ?? '';
  final symbol = code == 'PEN' ? 'S/' : code;
  return '${symbol.isEmpty ? '' : '$symbol '}${nexaAmountLabel(amount)}';
}
