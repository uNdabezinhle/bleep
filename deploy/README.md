# Test, pilot, and production

Three always-on boxes. One provider. Johannesburg, so `BLEEP_REGION=ZA-JHB` is the place the machines sit.

Sleeping app platforms (Render, Railway, Koyeb, Fly.io's trial) do not fit. The relay holds WebSockets, Postgres has to stay up, and coturn needs a public IP plus UDP `3478` and `49160–49200`. Google's always-free VM is US-only. AWS and Azure free compute expire. A Cloudflare quick tunnel, including the one in `scripts/spin.ps1`, publishes HTTP only.

## Account

[Oracle Cloud Always Free](https://www.oracle.com/cloud/free/), home region **South Africa Central (Johannesburg, `af-johannesburg-1`)**.

The home region is permanent. Always Free compute runs only there. If signup will not offer Johannesburg, stop. A box in another region labelled `ZA-JHB` is the wrong pin.

The card verifies the account. Oracle reverses a small charge. Prepaid and virtual cards are often refused. One Always Free account per person.

Checked November 2026, after the Arm cut. Confirm the Always Free page on the day you create the tenancy. Figures that drive this layout:

| Resource | Allowance |
| --- | --- |
| Ampere A1 | 2 OCPU and 12 GB, always on |
| AMD micro | Up to 2 × 1 GB `VM.Standard.E2.1.Micro` |
| Block storage | 200 GB. A boot volume is about 50 GB |
| Egress | 10 TB / month, shared |

Use three VMs and one backup disk. A fourth VM spends the disk the backups need.

| Box | Shape | `BLEEP_ENV` | Name |
| --- | --- | --- | --- |
| test | AMD micro + 2 GB swap | `test` | `test.<domain>` |
| pilot | A1, 1 OCPU, 6 GB | `pilot` | `pilot.<domain>` |
| production | A1, 1 OCPU, 6 GB | `prod` | the bare domain, or `prod.<domain>` |
| backups | 50 GB block volume, attached to production only | | mounted at `/var/backups/bleep` |

Leave the second AMD micro uncreated. Boot disks are ~50 GB each. Three of them plus the backup volume fill the 200 GB.

There is no SLA. Oracle may remove an Always Free VM that stays under about 20% CPU, network, and memory for 7 days. A quiet test box is the likely one. The backup cron is real work. Do not run a fake load to look busy.

Ampere capacity in Johannesburg is often "out of host capacity." This region has one availability domain, so there is no second domain to try. Create the AMD micro first (`VM.Standard.E2.1.Micro`). Retry A1 later at 1 OCPU and 6 GB, with the fault domain left unset. Delete failed instances and unattached boot volumes before each retry. Do not switch the tenancy to paid just to skip that queue unless you intend to pay.

Cloudflare does not replace these VMs. Workers and Pages cannot run Postgres or a process that stays up. A Cloudflare tunnel publishes HTTP only, not coturn's UDP. Containers are a paid Workers plan, they sleep, and they are not pinned to Johannesburg. Realtime TURN (`turn.cloudflare.com`) has a free egress allowance, but the relay is Cloudflare's global network, not an in-region `ZA-JHB` TURN. Use Cloudflare for DNS only, with the records set to DNS only.

## Network

One VCN. Each VM gets its own security list. Ingress:

| Port | From | Why |
| --- | --- | --- |
| 22/TCP | your admin IP | SSH |
| 80/TCP, 443/TCP | the internet | Caddy, certificate, invite client, `/v1` |
| 3478/TCP and UDP, 49160–49200/UDP | the internet | coturn (host network, so the relay ports are the VM's own ports) |

Do not open `8090`. That binding is for a laptop. On these VMs, Caddy is the public HTTP door. Leave Cloudflare (or any other proxy) on DNS-only. An HTTP proxy does not carry TURN, and it will idle-close the wake WebSocket.

Each VM has its own Postgres volume and its own secrets. No shared database.

## On each VM

Ubuntu 24.04 image, both arm64 and amd64. The compose images already publish both.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
# log out and back in so the group applies

sudo fallocate -l 2G /swapfile   # test micro only
sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

sudo mkdir -p /opt/bleep
sudo chown "$USER" /opt/bleep
git clone <this repo> /opt/bleep
cd /opt/bleep
cp .env.example .env
```

Fill `.env` from the comments in `.env.example`. New secrets per box (`openssl rand -hex 32`). `TURN_EXTERNAL_IP` is `public/private`, the two addresses of **this** VM (Oracle shows the public IP on the instance and the private IP on the VNIC). `TURN_PASS` is compiled into the web client. Changing it later means rebuilding the `web` image.

Point the hostname at the VM's public IP. Then:

```bash
docker compose -f docker-compose.yml -f deploy/compose.public.yml up --build -d
```

Caddy fetches the certificate on first start. Port 80 must be reachable.

Production only, after the 50 GB volume is mounted at `/var/backups/bleep`:

```bash
chmod +x deploy/backup.sh
# 02:15 UTC every day
( crontab -l 2>/dev/null; echo '15 2 * * * cd /opt/bleep && ./deploy/backup.sh >> /var/backups/bleep/backup.log 2>&1' ) | crontab -
./deploy/backup.sh
```

Dumps older than 14 days are deleted. Copy one off the VM if you need a copy that survives this disk.

## Check

On each hostname:

1. `curl -fsS -H "X-Bleep-Region: ZA-JHB" https://<host>/v1/health`
2. Two browsers, `https://<host>/?p=alice` and `/?p=bob`. Request to message, then a text.
3. `https://<host>/download/bleep.apk` and the hash on `/hashes.html`, after you have published an APK into the web container the same way `scripts/spin.sh` does.
4. A 1:1 call between two networks. The browser uses `turn:<the same host>:3478` with the user and password from `.env`.

Paste that box URL into the phone as Public Bleep. The debug APK is not Play-signed. This tree is not an audit.

## Local laptop

Unchanged: `docker compose up --build`, then http://localhost:8090. Leave `TURN_EXTERNAL_IP` empty. Defaults stay `bleep` / `bleep-dev` for the invite client on your machine.
