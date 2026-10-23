#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 server (AWS Lightsail Mumbai, 2 GB) for docker-compose.prod.yml.
# Run as the default `ubuntu` user: bash scripts/server-setup.sh   (it uses sudo; safe to run again)
#   - security updates installed automatically
#   - 2 GB swap, so building the images next to the running app cannot run out of memory
#   - Docker Engine + the compose plugin from Docker's own apt repository, with log rotation
#   - the server clock in IST (the app stores UTC; this only makes logs and backup times read naturally)
# The firewall is Lightsail's (Networking tab): allow 22, 80 and 443 TCP, and 443 UDP. See docs/go-live.md.
set -euo pipefail

echo "== Updates =="
sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl git unattended-upgrades
sudo dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== Time zone =="
sudo timedatectl set-timezone Asia/Kolkata

echo "== Swap =="
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf
  sudo sysctl -p /etc/sysctl.d/99-swappiness.conf
fi

echo "== Docker =="
if ! command -v docker >/dev/null; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  sudo chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
# Keep container logs from filling the disk.
echo '{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "5" } }' \
  | sudo tee /etc/docker/daemon.json >/dev/null
sudo systemctl enable --now docker
sudo systemctl restart docker
sudo usermod -aG docker "$USER"

echo
echo "Done. Log out and back in (so '$USER' can use docker), then continue with docs/go-live.md."
