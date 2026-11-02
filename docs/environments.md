# Environments and releases

## From a change to production

1. Every change arrives by pull request. `main` is protected: it merges only when the `ci` check is green.
2. CI builds and tests each module that changed, builds and tests the web app, and runs pre-prod.
3. After the merge is confirmed, the release is an annotated tag `vX.Y.Z` on `main`.
4. Only a tagged release is deployed, to the laptop and the phone ([running the live cells](runbooks.md)).

## Pre-prod is disposable

For every pull request, CI builds the host jar and the web app from that commit, starts the whole system from
nothing in Docker ([`preprod/docker-compose.yml`](https://github.com/SaiNayakk/cardrail/blob/main/preprod/docker-compose.yml)),
runs the [end-to-end tests](testing.md) through its public edge, keeps the logs and memory figures, and destroys
it, volumes included.

| Container | Runs | Memory limit |
|---|---|---|
| `postgres` | Postgres 18, the version the phone runs | 192 MB |
| `issuing` | Kestrel Bank | 224 MB |
| `network` | Railnet and the trace | 256 MB |
| `acquiring` | Gateway, vault, acquirer, demo shop | 320 MB |
| `web` | The web app and the public edge (nginx) | 64 MB |

Every host runs with the phone's JVM settings (serial GC, small heaps, C1 only), so a memory regression fails
in pre-prod first. The secrets in the compose file are throwaway values for this environment only.

To run it on a laptop:

```bash
./mvnw -DskipTests install
docker compose -f preprod/docker-compose.yml up -d --build --wait
./mvnw -pl e2e test -De2e.baseUrl=http://127.0.0.1:8280
docker compose -f preprod/docker-compose.yml down -v
```

## The public edge

The `web` container is the only thing published. It serves the web app and forwards just these:

| Path | To |
|---|---|
| `/hosted/` | The vault's card form and its token endpoint |
| `/api/shop/` | The demo shop's server |
| `/api/railpay/` | Railpay's merchant API (`/v1/...`), for trying it with curl |
| `/api/test-cards` | Kestrel Bank's published test cards |
| `/api/network/` | Railnet's public BIN table |
| `/api/trace/` | The live trace |

`/internal` and `/actuator` are never forwarded (E2E-06).

## Two cells, as live

[`preprod/two-cells-compose.yml`](https://github.com/SaiNayakk/cardrail/blob/main/preprod/two-cells-compose.yml) runs the
live layout ([ADR-015](decisions.md#adr-015-two-cells)): the banks on one Postgres, the rails on another, each
copying the other's schemas. CI runs the same journeys through it, then `TwoCellsTest`: a payment's records in both
databases (CELL-01), the phone's cell lost and the laptop taking its banks over with every card and payment
(CELL-02), and the phone's cell handed its banks back with what happened while it was away (CELL-03).
