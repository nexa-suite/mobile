# BC-06 Fulfillment & Delivery

Status: AS-IS client projection.

Picking, readiness, dispatch, delivery execution, handoffs, operational exceptions and delivery evidence.

The API owns authoritative business decisions. Client domain types represent
immutable facts and local value constraints; application coordinates requests
and local command staging. Infrastructure implements HTTP, serialization and
scoped storage ports. Presentation renders application state. Layer modules
exist only where this client has code; no empty runtime capability is implied.
