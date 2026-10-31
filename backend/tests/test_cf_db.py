from pathlib import Path
import pytest
from app.db import Store
from app.schemas import CFAccountIn

KEY = bytes(range(32))

@pytest.fixture()
def store(tmp_path: Path) -> Store:
    return Store(tmp_path / "data.db", KEY)

def test_cf_account_crud(store: Store) -> None:
    # 1. Create account
    acc = store.create_cf_account(
        name="Akun Pribadi",
        token="cfat_1234567890abcdef1234567890abcdef12345678",
        account_id="c5a58505f8fc830979277c99db4be0c8",
        account_name="Aithendy's Account"
    )
    assert acc["id"] is not None
    assert acc["name"] == "Akun Pribadi"
    assert acc["token_masked"].startswith("cfat_")
    assert acc["token_masked"].endswith("5678")
    assert "1234567890" not in acc["token_masked"]
    assert acc["account_id"] == "c5a58505f8fc830979277c99db4be0c8"

    # 2. List accounts
    accounts = store.list_cf_accounts()
    assert len(accounts) == 1
    assert accounts[0]["id"] == acc["id"]
    assert accounts[0]["name"] == "Akun Pribadi"

    # 3. Get account with full token (internal)
    full_acc = store.get_cf_account(acc["id"])
    assert full_acc is not None
    assert full_acc["token"] == "cfat_1234567890abcdef1234567890abcdef12345678"
    assert full_acc["account_id"] == "c5a58505f8fc830979277c99db4be0c8"

    # 4. Delete account
    deleted = store.delete_cf_account(acc["id"])
    assert deleted is True
    assert len(store.list_cf_accounts()) == 0
    assert store.get_cf_account(acc["id"]) is None


def test_cf_account_update(store: Store) -> None:
    acc = store.create_cf_account(
        name="Akun Awal",
        token="cfat_initialtoken1234567890abcdef12345678",
        account_id="acc-initial-id",
        account_name="Org Awal"
    )

    # 1. Update name only
    updated = store.update_cf_account(acc["id"], name="Akun Diedit")
    assert updated is not None
    assert updated["id"] == acc["id"]
    assert updated["name"] == "Akun Diedit"
    assert updated["account_id"] == "acc-initial-id"
    # verify persistence
    loaded = store.get_cf_account(acc["id"])
    assert loaded["name"] == "Akun Diedit"
    assert loaded["token"] == "cfat_initialtoken1234567890abcdef12345678"

    # 2. Update name and token
    updated2 = store.update_cf_account(
        acc["id"],
        name="Akun Token Baru",
        token="cfat_newtoken9876543210abcdef9876543210",
        account_id="acc-new-id",
        account_name="Org Baru"
    )
    assert updated2 is not None
    assert updated2["name"] == "Akun Token Baru"
    assert updated2["account_id"] == "acc-new-id"
    assert updated2["account_name"] == "Org Baru"
    loaded2 = store.get_cf_account(acc["id"])
    assert loaded2["token"] == "cfat_newtoken9876543210abcdef9876543210"

    # 3. Update non-existent account returns None
    assert store.update_cf_account(99999, name="Non Existent") is None

