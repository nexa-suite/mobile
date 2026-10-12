# BC-01 Tenant & Access Governance

Status: AS-IS client projection.

Identity, authorized membership and Tenant/Workspace context.

The API owns authoritative business decisions. Client domain types represent
immutable facts and local value constraints; application coordinates requests
and local command staging. Infrastructure implements HTTP, serialization and
scoped storage ports. Presentation renders application state. Layer modules
exist only where this client has code; no empty runtime capability is implied.
