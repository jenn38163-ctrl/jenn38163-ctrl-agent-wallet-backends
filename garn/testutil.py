import os
from store import Store

def make_store():
    """SQLite in memory by default. Set TEST_DATABASE_URL to a throwaway PostgreSQL database to run the same tests there."""
    url = os.environ.get("TEST_DATABASE_URL")
    if not url: return Store("sqlite:///:memory:")
    s = Store(url)
    with s._pg.connect(url) as c:
        c.execute("TRUNCATE users, assets, collateral, events, ledger, parties, tokens, holdings, cards, accounts, periods, journal, journal_lines, "
                  "pools, tranches, loans, loan_txns, waterfall_runs, positions, lots, sw_projects, sw_costs, sw_amort, documents, "
                  "jurisdiction_rules, loan_meta, card_wallets CASCADE")
    return s
