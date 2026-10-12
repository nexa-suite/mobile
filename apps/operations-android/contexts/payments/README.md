# BC-08 Payments

Status: Operations wallet-refund client slice implemented and focused technical
checks pass; Product/UX acceptance and system acceptance are not claimed.

The slice lists server-provided refundable sales-order candidates and submits a
full or partial PEN refund with a required reason. It preserves four-decimal
server values and an encrypted, membership-scoped frozen intent for explicit
same-key retries after an ambiguous outcome. The API remains authoritative;
this client does not select a buyer wallet, automatically retry, or couple a
refund to order cancellation. A field-request payment preference remains a
BC04 input and does not implement payment authority.

The API owns authoritative business decisions. Client domain types represent
immutable facts and local value constraints; application coordinates requests
and local command staging. Infrastructure implements HTTP, serialization and
scoped storage ports. Presentation renders application state. Layer modules
exist only where this client has code; no empty runtime capability is implied.

Canonical references: [context map](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/bounded-context-module-map.md)
and [logical layers](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/logical-layering.md).
