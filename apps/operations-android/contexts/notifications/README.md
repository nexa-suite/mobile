# BC-10 Notifications

Status: Operations inbox and preference source implemented and technically
verified in the current working tree.

Operations source includes a scoped inbox, read/unread actions and preferences
for the accepted `IN_APP` and `EMAIL` channels. API remains authoritative for
Tenant/Workspace scope, permissions and notification state. Push delivery and
protected deep-link behavior remain outside this source slice.

Inbox access requires `notification.read`; preference changes require
`notification.manage_preferences`. Client state clears when its active route or
authority becomes invalid. Strict Operations architecture, formatting, lint,
JVM, debug assembly and API 37/API 29 instrumentation gates passed. The
connected suites each recorded 65 tests with no failures, errors or skips.
Product acceptance, system acceptance and live service compatibility remain
separate and unverified.

Canonical references: [context map](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/bounded-context-module-map.md)
and [logical layers](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/logical-layering.md).
