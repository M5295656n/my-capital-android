#!/usr/bin/env python3
"""Live schema and coverage check for the public historical dividend datasets."""
import json
import sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE = "https://raw.githubusercontent.com/amchercashin/heroincome-data/main/data/stocks"
MAX_AGE_DAYS = 30
REQUIRED = {"TATN", "OZPH", "T", "X5"}
LIVE_FIXTURES = {}


def fetch_json(url):
    last_error = None
    for attempt in range(3):
        try:
            request = Request(url, headers={
                "Accept": "application/json",
                "User-Agent": "MyCapital-CI/1.0",
                "Cache-Control": "no-cache",
            })
            with urlopen(request, timeout=20) as response:
                return json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = exc
    raise RuntimeError(f"{url}: {last_error}")


def recent(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    age = (date.today() - parsed.astimezone(timezone.utc).date()).days
    return 0 <= age <= MAX_AGE_DAYS


def validate_payment(source, row):
    if not isinstance(row, dict):
        return False
    if source == "dohod":
        if row.get("isForecast") is True:
            return False
    elif str(row.get("status", "")).lower() != "paid":
        return False
    currency = str(row.get("currency") or "RUB").upper()
    if currency != "RUB":
        return False
    try:
        event_date = date.fromisoformat(str(row.get("recordDate", ""))[:10])
        amount = float(row.get("amount"))
    except (TypeError, ValueError):
        return False
    return event_date <= date.today() and amount > 0 and amount < float("inf")


def inspect_dataset(source, ticker):
    url = f"{BASE}/{source}/{ticker}.json"
    payload = fetch_json(url)
    if str(payload.get("ticker", "")).upper() != ticker:
        raise RuntimeError(f"{source}/{ticker}: issuer identity mismatch: {payload.get('ticker')!r}")
    if not recent(str(payload.get("scrapedAt", ""))):
        raise RuntimeError(f"{source}/{ticker}: stale or invalid scrapedAt={payload.get('scrapedAt')!r}")
    payments = payload.get("payments")
    if not isinstance(payments, list):
        raise RuntimeError(f"{source}/{ticker}: payments is not an array")
    valid = [row for row in payments if validate_payment(source, row)]
    return ticker, len(payments), len(valid), payload


def check_source(source):
    index = fetch_json(f"{BASE}/{source}/index.json")
    if not recent(str(index.get("updatedAt", ""))):
        raise RuntimeError(f"{source}: stale index updatedAt={index.get('updatedAt')!r}")
    tickers = sorted({str(value).upper() for value in index.get("tickers", [])})
    if not tickers:
        raise RuntimeError(f"{source}: empty ticker index")
    if int(index.get("tickersCount", len(tickers))) != len(tickers):
        raise RuntimeError(f"{source}: tickersCount does not match unique index rows")
    results, errors = {}, []
    LIVE_FIXTURES[source] = {"index": index, "histories": {}}
    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = {pool.submit(inspect_dataset, source, ticker): ticker for ticker in tickers}
        for future in as_completed(futures):
            ticker = futures[future]
            try:
                _, total, valid, payload = future.result()
                results[ticker] = (total, valid)
                LIVE_FIXTURES[source]["histories"][ticker] = payload
            except Exception as exc:
                errors.append(f"{ticker}: {exc}")
    coverage = sum(1 for _, valid in results.values() if valid > 0)
    print(f"{source}: index={len(tickers)}, fetched={len(results)}, with_paid_RUB_history={coverage}, updatedAt={index.get('updatedAt')}")
    if errors:
        print(f"{source}: {len(errors)} dataset errors:")
        for error in errors[:20]:
            print(f"  - {error}")
    missing_required = sorted(REQUIRED - set(results))
    empty_required = sorted(t for t in REQUIRED if t in results and results[t][1] == 0)
    if missing_required or empty_required:
        raise RuntimeError(f"{source}: required coverage missing; absent={missing_required}, no_paid_rows={empty_required}")
    # Every ticker advertised by the index must have a retrievable, correctly
    # identified, fresh JSON dataset. A zero-dividend issuer may have zero paid rows.
    if errors:
        raise RuntimeError(f"{source}: {len(errors)} indexed datasets failed validation")
    return set(tickers), coverage


def main():
    all_tickers = set()
    for source in ("dohod", "smartlab"):
        tickers, coverage = check_source(source)
        all_tickers.update(tickers)
    print(f"Combined indexed universe: {len(all_tickers)} unique tickers")
    print("Required issuers TATN/OZPH/T/X: both public indexes include each; live JSON validation passed.")
    fixture_path = Path("app/build/dividend-audit/fixtures.json")
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_path.write_text(json.dumps(LIVE_FIXTURES, ensure_ascii=False), encoding="utf-8")
    print(f"Saved live issuer histories for production forecast audit: {fixture_path}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"DIVIDEND SOURCE CHECK FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
