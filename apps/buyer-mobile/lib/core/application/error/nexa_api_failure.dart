/// Stable application-facing failure translated from a Nexa API response.
///
/// This contract is independent of Flutter, IO, and HTTP client packages. The
/// infrastructure adapter retains protocol metadata needed by application
/// workflows while keeping transport response types out of feature code.
final class NexaApiFailure implements Exception {
  const NexaApiFailure({
    required this.code,
    required this.userMessage,
    this.statusCode,
    this.correlationId,
    this.retryable = false,
  });

  final String code;
  final String userMessage;
  final int? statusCode;
  final String? correlationId;
  final bool retryable;

  @override
  String toString() => 'NexaApiFailure($code, $statusCode)';
}
