# BC-08 Payments

Status: Canonical ownership mapped; client capability not implemented.

No standalone Operations client capability is implemented. A field-request payment preference is a BC04 input and does not implement payment authority.

The API owns authoritative business decisions. Client domain types represent
immutable facts and local value constraints; application coordinates requests
and local command staging. Infrastructure implements HTTP, serialization and
scoped storage ports. Presentation renders application state. Layer modules
exist only where this client has code; no empty runtime capability is implied.

Canonical references: [context map](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/bounded-context-module-map.md)
and [logical layers](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/logical-layering.md).
