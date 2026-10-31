import json
import urllib.error
import urllib.request
from typing import Any, Callable

from fastapi import APIRouter, Depends, HTTPException, Query

from .schemas import CFAccountIn, CFAccountOut, CFAccountUpdateIn, CFAccountVerifyIn, CFRouteIn

CF_BASE = "https://api.cloudflare.com/client/v4"


def cf_request(method: str, path: str, token: str, data: dict[str, Any] | None = None) -> dict[str, Any]:
    url = f"{CF_BASE}{path}"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "User-Agent": "TermDeck-Cloudflare/1.0",
    }
    body = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)

    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read().decode("utf-8")
            return json.loads(content)
    except urllib.error.HTTPError as exc:
        err_msg = f"Cloudflare API error ({exc.code})"
        try:
            err_json = json.loads(exc.read().decode("utf-8"))
            if err_json.get("errors") and len(err_json["errors"]) > 0:
                err_msg = err_json["errors"][0].get("message", err_msg)
        except Exception:
            pass
        raise HTTPException(status_code=exc.code if exc.code < 500 else 502, detail=err_msg)
    except urllib.error.URLError as exc:
        raise HTTPException(status_code=504, detail=f"Failed to connect to Cloudflare: {exc.reason}")


def register_cloudflare_routes(router: APIRouter, unlocked_dep: Callable[..., Any]) -> None:
    def _get_account_or_404(ctx: Any, account_id: int) -> dict[str, Any]:
        acc = ctx.store.get_cf_account(account_id)
        if not acc:
            raise HTTPException(status_code=404, detail="Akun Cloudflare tidak ditemukan.")
        return acc

    @router.post("/cf/accounts/verify")
    def verify_token(payload: CFAccountVerifyIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        token = payload.token.strip()
        res = cf_request("GET", "/accounts", token)
        accounts = res.get("result", [])
        if not accounts:
            raise HTTPException(status_code=400, detail="Token tidak memiliki akses ke akun Cloudflare mana pun.")
        account_id = accounts[0]["id"]
        account_name = accounts[0].get("name", "Unnamed Account")

        # Capability probe for DNS records
        has_dns_access = False
        try:
            z_res = cf_request("GET", f"/zones?status=active&account.id={account_id}&per_page=1", token)
            zones = z_res.get("result", [])
            if zones:
                zid = zones[0]["id"]
                cf_request("GET", f"/zones/{zid}/dns_records?per_page=1", token)
                has_dns_access = True
        except Exception:
            has_dns_access = False

        return {
            "account_id": account_id,
            "account_name": account_name,
            "has_dns_access": has_dns_access,
        }

    @router.get("/cf/accounts", response_model=list[CFAccountOut])
    def list_accounts(ctx: Any = Depends(unlocked_dep)) -> list[dict[str, Any]]:
        return ctx.store.list_cf_accounts()

    @router.post("/cf/accounts", response_model=CFAccountOut)
    def create_account(payload: CFAccountIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        token = payload.token.strip()
        res = cf_request("GET", "/accounts", token)
        accounts = res.get("result", [])
        if not accounts:
            raise HTTPException(status_code=400, detail="Token tidak valid atau tidak memiliki akses akun.")
        account_id = accounts[0]["id"]
        account_name = accounts[0].get("name", "")
        return ctx.store.create_cf_account(
            name=payload.name.strip(),
            token=token,
            account_id=account_id,
            account_name=account_name,
        )

    @router.put("/cf/accounts/{account_id}", response_model=CFAccountOut)
    def update_account(account_id: int, payload: CFAccountUpdateIn, ctx: Any = Depends(unlocked_dep)) -> dict[str, Any]:
        existing = _get_account_or_404(ctx, account_id)
        name = payload.name.strip()
        new_token = payload.token.strip() if payload.token and payload.token.strip() else None

        cf_acc_id = None
        cf_acc_name = None
        if new_token:
            res = cf_request("GET", "/accounts", new_token)
            accounts = res.get("result", [])
            if not accounts:
                raise HTTPException(status_code=400, detail="Token tidak valid atau tidak memiliki akses akun.")
            cf_acc_id = accounts[0]["id"]
            cf_acc_name = accounts[0].get("name", "")

        updated = ctx.store.update_cf_account(
            account_id,
            name=name,
            token=new_token,
            account_id=cf_acc_id,
            account_name=cf_acc_name,
        )
        if not updated:
            raise HTTPException(status_code=404, detail="Akun Cloudflare tidak ditemukan.")
        return updated

    @router.delete("/cf/accounts/{account_id}")
    def delete_account(account_id: int, ctx: Any = Depends(unlocked_dep)) -> dict[str, bool]:
        ok = ctx.store.delete_cf_account(account_id)
        if not ok:
            raise HTTPException(status_code=404, detail="Akun Cloudflare tidak ditemukan.")
        return {"ok": True}

    @router.get("/cf/accounts/{account_id}/tunnels")
    def list_tunnels(account_id: int, ctx: Any = Depends(unlocked_dep)) -> list[dict[str, Any]]:
        acc = _get_account_or_404(ctx, account_id)
        cf_acc_id = acc["account_id"]
        token = acc["token"]
        res = cf_request("GET", f"/accounts/{cf_acc_id}/cfd_tunnel?is_deleted=false", token)
        tunnels = res.get("result", [])
        return [
            {
                "id": t["id"],
                "name": t.get("name", ""),
                "status": t.get("status", "inactive"),
                "conns_count": len(t.get("connections", [])),
                "created_at": t.get("created_at", ""),
                "remote_config": t.get("remote_config", False),
            }
            for t in tunnels
        ]

    @router.get("/cf/accounts/{account_id}/zones")
    def list_zones(account_id: int, ctx: Any = Depends(unlocked_dep)) -> list[dict[str, Any]]:
        acc = _get_account_or_404(ctx, account_id)
        cf_acc_id = acc["account_id"]
        token = acc["token"]
        res = cf_request("GET", f"/zones?status=active&account.id={cf_acc_id}", token)
        zones = res.get("result", [])
        return [
            {
                "id": z["id"],
                "name": z["name"],
                "status": z.get("status", "active"),
            }
            for z in zones
        ]

    @router.get("/cf/accounts/{account_id}/tunnels/{tunnel_id}/routes")
    def list_tunnel_routes(account_id: int, tunnel_id: str, ctx: Any = Depends(unlocked_dep)) -> list[dict[str, Any]]:
        acc = _get_account_or_404(ctx, account_id)
        cf_acc_id = acc["account_id"]
        token = acc["token"]
        res = cf_request("GET", f"/accounts/{cf_acc_id}/cfd_tunnel/{tunnel_id}/configurations", token)
        config = res.get("result", {}).get("config", {})
        ingress = config.get("ingress", [])
        return [
            {
                "hostname": rule["hostname"],
                "service": rule["service"],
            }
            for rule in ingress
            if "hostname" in rule and "service" in rule
        ]

    @router.post("/cf/accounts/{account_id}/tunnels/{tunnel_id}/routes")
    def add_tunnel_route(
        account_id: int,
        tunnel_id: str,
        payload: CFRouteIn,
        ctx: Any = Depends(unlocked_dep),
    ) -> dict[str, Any]:
        acc = _get_account_or_404(ctx, account_id)
        cf_acc_id = acc["account_id"]
        token = acc["token"]
        hostname = payload.hostname.strip().lower()
        service = payload.service.strip()
        zone_id = payload.zone_id.strip() if payload.zone_id else None

        # 1. Update ingress config on Cloudflare Tunnel
        cfg_res = cf_request("GET", f"/accounts/{cf_acc_id}/cfd_tunnel/{tunnel_id}/configurations", token)
        current_ingress = cfg_res.get("result", {}).get("config", {}).get("ingress", [])

        new_ingress = []
        catch_all = None
        for rule in current_ingress:
            if "hostname" not in rule and rule.get("service") == "http_status:404":
                catch_all = rule
            elif rule.get("hostname", "").lower() == hostname:
                continue
            else:
                new_ingress.append(rule)

        new_ingress.append({"hostname": hostname, "service": service})
        if catch_all:
            new_ingress.append(catch_all)
        else:
            new_ingress.append({"service": "http_status:404"})

        cf_request(
            "PUT",
            f"/accounts/{cf_acc_id}/cfd_tunnel/{tunnel_id}/configurations",
            token,
            data={"config": {"ingress": new_ingress}},
        )

        # 2. Sync DNS CNAME on the Zone if zone_id provided
        dns_synced = False
        dns_warning: str | None = None
        target_cname = f"{tunnel_id}.cfargotunnel.com"

        if zone_id:
            try:
                dns_list = cf_request("GET", f"/zones/{zone_id}/dns_records?type=CNAME&name={hostname}", token)
                records = dns_list.get("result", [])
                if records:
                    rec_id = records[0]["id"]
                    cf_request(
                        "PUT",
                        f"/zones/{zone_id}/dns_records/{rec_id}",
                        token,
                        data={"type": "CNAME", "name": hostname, "content": target_cname, "proxied": True},
                    )
                else:
                    cf_request(
                        "POST",
                        f"/zones/{zone_id}/dns_records",
                        token,
                        data={"type": "CNAME", "name": hostname, "content": target_cname, "proxied": True},
                    )
                dns_synced = True
            except HTTPException as exc:
                dns_synced = False
                dns_warning = (
                    f"Rute Ingress tunnel berhasil disimpan! Namun sinkronisasi DNS CNAME otomatis gagal ({exc.detail}). "
                    f"Token Cloudflare Anda belum memiliki izin 'Zone > DNS > Edit'. "
                    f"Pastikan record CNAME untuk '{hostname}' diarahkan ke '{target_cname}' di Cloudflare DNS."
                )
            except Exception as exc:
                dns_synced = False
                dns_warning = f"Rute tunnel disimpan, namun pembuatan DNS CNAME otomatis gagal: {exc}"

        return {
            "ok": True,
            "hostname": hostname,
            "service": service,
            "dns_synced": dns_synced,
            "dns_warning": dns_warning,
            "cname_target": target_cname,
        }

    @router.delete("/cf/accounts/{account_id}/tunnels/{tunnel_id}/routes")
    def delete_tunnel_route(
        account_id: int,
        tunnel_id: str,
        hostname: str = Query(...),
        zone_id: str | None = Query(None),
        ctx: Any = Depends(unlocked_dep),
    ) -> dict[str, Any]:
        acc = _get_account_or_404(ctx, account_id)
        cf_acc_id = acc["account_id"]
        token = acc["token"]
        hostname = hostname.strip().lower()

        # 1. Update ingress config
        cfg_res = cf_request("GET", f"/accounts/{cf_acc_id}/cfd_tunnel/{tunnel_id}/configurations", token)
        current_ingress = cfg_res.get("result", {}).get("config", {}).get("ingress", [])

        new_ingress = []
        catch_all = None
        for rule in current_ingress:
            if "hostname" not in rule and rule.get("service") == "http_status:404":
                catch_all = rule
            elif rule.get("hostname", "").lower() == hostname:
                continue
            else:
                new_ingress.append(rule)

        if catch_all:
            new_ingress.append(catch_all)
        else:
            new_ingress.append({"service": "http_status:404"})

        cf_request(
            "PUT",
            f"/accounts/{cf_acc_id}/cfd_tunnel/{tunnel_id}/configurations",
            token,
            data={"config": {"ingress": new_ingress}},
        )

        # 2. Delete DNS CNAME if zone_id provided
        if zone_id:
            try:
                dns_list = cf_request("GET", f"/zones/{zone_id}/dns_records?type=CNAME&name={hostname}", token)
                records = dns_list.get("result", [])
                for rec in records:
                    try:
                        cf_request("DELETE", f"/zones/{zone_id}/dns_records/{rec['id']}", token)
                    except Exception:
                        pass
            except Exception:
                pass

        return {"ok": True, "deleted": hostname}

    @router.get("/cf/accounts/{account_id}/tunnels/{tunnel_id}/token")
    def get_tunnel_token(account_id: int, tunnel_id: str, ctx: Any = Depends(unlocked_dep)) -> dict[str, str]:
        acc = _get_account_or_404(ctx, account_id)
        cf_acc_id = acc["account_id"]
        token = acc["token"]
        res = cf_request("GET", f"/accounts/{cf_acc_id}/cfd_tunnel/{tunnel_id}/token", token)
        token_str = res.get("result", "")
        return {"token": token_str}
