# Running the live cells

cardrail runs live on two devices beside Sprout ([ADR-015](decisions.md#adr-015-two-cells)): the phone (cell a)
runs the banks, the laptop (cell b) runs the rails, and each database copies the other's schemas. The public
address is `cards-saiworks.nncs.in`, a Cloudflare tunnel of cardrail's own with a connector in each cell.

| | Phone (cell a) | Laptop (cell b) |
|---|---|---|
| Runs | Kestrel Bank, Meridian (`banks`) | Gateway, vault, acquirer, demo shop, Railnet, trace (`rails`) |
| Started by | Backseat app `cardrail` (`~/cardrail/start.sh`) | Docker Compose `cardrail-live` ([`deploy/laptop/compose.yml`](https://github.com/SaiNayakk/cardrail/blob/main/deploy/laptop/compose.yml)) |
| Database | `cardrail` in Sprout's Postgres, role `cardrail`, at most 15 connections | the same, on the laptop |
| Memory cap | 400 MB: JVM, Postgres backends, edge, connector | 700 MB of Docker |
| Cell status | `http://<phone>:8209/cell/status` | `http://<laptop>:8209/cell/status` |

## Rules that come with Sprout's devices

- Deploy only a tagged release from `main`, and only while Sprout's witness shows both regions up.
- The phone keeps 1.2 GB available. The phone's starter waits for that, for Sprout to be up, and for Sprout's own
  takeover to finish. While cardrail runs, Sprout comes first: under 300 MB available for a minute, the JVM stops
  (`cardrail yielded memory to sprout` in `~/cardrail/logs`) and starts again only after 5 minutes with 1450 MB
  available, at most once in 30 minutes.
- Touch nothing of Sprout's: its containers, ports 8100, 18080 and 8195, its starter, its cellwatch, its tunnels.
  cardrail only reads Sprout's witness and, on the phone, its `cells/status.json`.
- No pre-prod on the laptop while the live cell runs there: pre-prod runs in CI.

## Deploy a release

```bash
deploy/release.sh v0.6.0
deploy/measure.sh
```

`release.sh` checks the witness, the phone's memory and that the tag is on `main`, builds from the tag alone,
copies the phone's files, migrates every schema in both cells, restarts the laptop's cell, then the phone's, and
makes sure each copies the other. Send the numbers `measure.sh` prints to the Sprout session after a deploy.

## When a cell is lost

Nothing to do at once. The other cell's watcher takes over by itself when the lost cell has been silent on the
home network for two minutes and Sprout's witness shows its device down. It stops copying, takes the lost cell's
schemas over, and restarts as the whole system; its edge then routes everything to itself. About a minute
without payments. A short loss of the phone needs no takeover: Railnet stands in for Kestrel Bank (ADR-007).

A cell that comes back while the other holds its half serves nothing (`TAKEN_OVER`) until it is handed back.

## Hand a cell's half back

Once the returning device has been healthy for a few minutes, from the laptop:

```bash
deploy/failback.sh a      # the phone came back
deploy/failback.sh b      # the laptop came back
```

The returning cell copies every table from the holder while the holder keeps serving; then the holder stops,
the returning cell takes the last rows and its half, both restart in their own halves and copy each other again.
Payments are refused for about a minute.

## Stop cardrail

```bash
docker compose -f deploy/laptop/compose.yml stop
ssh phone 'sh ~/cardrail/backseat.sh stop'
```

Stopped cells don't take each other over: Sprout's witness still shows both devices up.
