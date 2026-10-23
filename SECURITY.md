# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for a suspected security problem.

Email **contact@prismtabs.com** with a description of the issue and, if you have one, the
steps to reproduce it. You will get an acknowledgement; PrismTabs is maintained by one person,
so please allow a few days before following up.

## Scope

PrismTabs is a JetBrains IDE plugin. It makes no network calls of any kind — it neither
transmits nor collects any user data, and it has no server component. In practice that limits
the realistic scope to the plugin's handling of data already on your machine: project files and
paths, and its persisted group state.

Reports about the [prismtabs.com](https://prismtabs.com) website are welcome at the same address.

## Supported versions

Only the most recent release published to the JetBrains Marketplace is supported. Fixes ship as
a new Marketplace release rather than as a patch to an older version.
