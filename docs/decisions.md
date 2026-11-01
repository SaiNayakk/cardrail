# Decisions

Architecture decision records: the choice, why, and what lost. Newest last.

## ADR-001: One repository, a Maven module per service { #adr-001-monorepo }

**Decision.** Every service, the shared libraries and the contracts live in one repository as Maven modules
sharing one version. CI builds and tests each module on its own, only when it or what it is built from changed.
A release is an annotated tag `vX.Y.Z`.

**Why.** Sprout used one repository per service. Every change that crossed services became a chain of releases:
contracts first, then each service, then each host's manifest. Most real changes cross services. One repository
makes such a change one pull request, while per-module CI and versioned contracts keep the boundaries honest.

**Lost.** A service can't be released on its own schedule. Fine for one person; the module boundaries mean a
service could still be split out later.

## ADR-002: Java 21, Spring Boot 3, plain JDBC, Postgres 18 { #adr-002-stack }

**Decision.** The same stack as Sprout: Java 21 on Spring Boot 3, plain JDBC with Flyway, Postgres 18. The web
app is Angular 21.

**Why.** It is what banks and card networks run, Sprout's patterns carry over unchanged, and plain JDBC keeps
every query visible and saves memory on the phone.

**Lost.** A smaller runtime (Go) would use far less memory.

## ADR-003: Our own ISO 8583 codec { #adr-003-iso8583 }

**Decision.** Messages are encoded by a small codec written here (the `iso8583` module), not by jPOS or another
library. Railnet's dialect: ASCII text fields, a binary bitmap, binary PIN, chip and MAC fields, 2-byte length
framing on TCP.

**Why.** The point of the project is to understand the network layer. A codec is a few hundred lines; writing it
means knowing exactly what a bitmap, an LLVAR or a secondary bitmap is. Its tests check bytes written out by hand,
so they don't trust the codec's own arithmetic.

**Lost.** jPOS supports every dialect and packager. Railnet needs one.

## ADR-004: US dollars, global card rules { #adr-004-usd }

**Decision.** Card accounts, authorisations and settlement are in US dollars (ISO 4217 `840`), in cents. Step-up
(3-D Secure style) is asked for when the issuer judges the risk high, as in the US, not on every online payment.

**Why.** It matches how Visa, Mastercard and American Express work in their largest market.

**Lost.** Sprout's wallet is in rupees, so its card top-ups are cross-border: the network converts the amount
(fields 6, 10 and 51). That is a feature to build, not a shortcut, in the Sprout phase.

## ADR-005: Three hosts by failure domain { #adr-005-hosts }

**Decision.** As in Sprout, services are built separately but run together in hosts, one JVM each:
`acquiring` (gateway, vault, acquirer), `network` (switch, closed loop, trace) and `issuing` (the issuer). An
all-in-one `cell` host runs everything in one JVM as the other cell's standby.

**Why.** The issuer gets its own JVM so the switch can be tested standing in while the issuer is down, which is
the network's most interesting failure. Grouping the rest keeps the phone's share to about 550 MB plus a 300 MB
standby.

**Lost.** The closed loop shares a JVM with the switch it is meant to be independent of.
