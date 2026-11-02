"""Makes the live cells' secrets, once, into files that are never committed or printed (ADR-015).

Both cells hold the same keys: holding the other's half means running its services with its keys.

    python deploy/secrets.py            writes deploy/secrets.env if it doesn't exist (never overwrites)
    python deploy/secrets.py --cell b   writes deploy/laptop/.env: the keys, plus every service's database password
    python deploy/secrets.py --cell a   writes deploy/phone/secrets.env, the same for the phone

The database passwords are the ones Sprout's session set for the `cardrail` role: this cell's from
CARDRAIL_DB_PASSWORD and the other cell's from CARDRAIL_PEER_DB_PASSWORD, both read from the environment or
from deploy/db-passwords.env (also never committed).
"""
import base64
import os
import secrets
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
KEYS = HERE / "secrets.env"
SERVICES = ["ISSUER", "MERIDIAN", "TRACE", "NETWORK", "ACQUIRER", "VAULT", "GATEWAY"]


def key32():
    return base64.b64encode(secrets.token_bytes(32)).decode()


def token(prefix, n):
    return prefix + secrets.token_urlsafe(n).replace("-", "").replace("_", "")[:n]


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                name, value = line.split("=", 1)
                values[name.strip()] = value.strip()
    return values


def write_private(path, lines):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    os.chmod(path, 0o600)


def make_keys():
    if KEYS.exists():
        return
    write_private(KEYS, [
        "# cardrail's live keys, made by deploy/secrets.py. Never commit or print this file.",
        f"ISSUER_PAN_KEY={key32()}",
        f"ISSUER_HASH_KEY={key32()}",
        f"ISSUER_CVK={key32()}",
        f"VAULT_KEY={key32()}",
        f"NETWORK_KEY={key32()}",
        f"MERIDIAN_KEY={key32()}",
        f"CARDRAIL_INTERNAL_KEY={secrets.token_urlsafe(32)}",
        f"DEMOSHOP_SECRET_KEY={token('sk_test_', 32)}",
        f"DEMOSHOP_PUBLISHABLE_KEY={token('pk_test_', 24)}",
        f"DEMOSHOP_WEBHOOK_SECRET={token('whsec_', 32)}",
    ])
    print(f"wrote {KEYS.relative_to(HERE.parent)}")


def make_cell(cell):
    make_keys()
    passwords = read_env(HERE / "db-passwords.env")
    own = os.environ.get("CARDRAIL_DB_PASSWORD") or passwords.get(f"CELL_{cell.upper()}_DB_PASSWORD")
    peer = os.environ.get("CARDRAIL_PEER_DB_PASSWORD") or passwords.get(f"CELL_{'B' if cell == 'a' else 'A'}_DB_PASSWORD")
    if not own or not peer:
        sys.exit("both cells' database passwords are needed (deploy/db-passwords.env: CELL_A_DB_PASSWORD, CELL_B_DB_PASSWORD)")
    keys = [line for line in KEYS.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#")]
    target = HERE / ("laptop/.env" if cell == "b" else "phone/secrets.env")
    write_private(target, ["# made by deploy/secrets.py; never commit or print"] + keys
                  + [f"CARDRAIL_DB_PASSWORD={own}", f"CARDRAIL_PEER_DB_PASSWORD={peer}"]
                  + [f"{s}_DB_PASSWORD={own}" for s in SERVICES])
    print(f"wrote {target.relative_to(HERE.parent)}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--cell" and sys.argv[2] in ("a", "b"):
        make_cell(sys.argv[2])
    elif len(sys.argv) == 1:
        make_keys()
    else:
        sys.exit(__doc__)
