# BC-10 Notifications

Status: Canonical ownership mapped; client capability not implemented.

No standalone Operations notification delivery capability is implemented. Push and protected deep-link behavior require their accepted slice.

The API owns authoritative business decisions. Client domain types represent
immutable facts and local value constraints; application coordinates requests
and local command staging. Infrastructure implements HTTP, serialization and
scoped storage ports. Presentation renders application state. Layer modules
exist only where this client has code; no empty runtime capability is implied.
