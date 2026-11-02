final class BuyerProfile {
  const BuyerProfile({
    required this.userId,
    required this.email,
    required this.displayName,
    required this.language,
    required this.timezone,
    required this.version,
    this.phone,
    this.firstName,
    this.lastName,
    this.documentType,
    this.documentNumber,
    this.documentCountryCode,
  });
  final String userId, email, displayName, language, timezone;
  final int version;
  final String? phone,
      firstName,
      lastName,
      documentType,
      documentNumber,
      documentCountryCode;
}

final class BuyerProfileEdit {
  const BuyerProfileEdit({
    required this.displayName,
    required this.firstName,
    required this.lastName,
    required this.phone,
    required this.language,
  });
  final String displayName, firstName, lastName, phone, language;
}
