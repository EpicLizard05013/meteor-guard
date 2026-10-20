# 🛡️ MeteorAntiESP & AntiFreecam

A lightweight, high-performance **Paper 1.21.11** anti-cheat plugin designed to block ESP, Wallhack, X-Ray, and Freecam cheats without destroying vanilla server functionality or hiding players from the Tablist.

---

## ✨ Key Features

* 👁️ **Tablist-Safe Anti-ESP:** Obstructs players from rendering through solid walls and terrain while ensuring their names remain permanently visible on the Tablist.
* ⛏️ **AntiFreecam Subterranean Masking:** Surface players are sent fake solid stone data for chunks below the threshold, preventing camera-flying mods and underground X-Ray views.
* ⚡ **Ultra-Lightweight & Async:** All line-of-sight checks run asynchronously on background threads to keep server TPS at a solid 20.0.
* 🛡️ **Staff Exemption & Bypass:** Simple commands and permissions allow staff members and spectators to bypass occlusion restrictions seamlessly.
* ⚙️ **Fully Configurable:** Easily tune detection radii, Y-level thresholds, raycast precision, and custom transparent block lists.

---

## 📦 Requirements

* **Server Software:** Paper / Purpur 1.21.11 (Java 21)
* **Required Dependencies:**
  * [ProtocolLib](https://github.com/dmulloy2/ProtocolLib) (v5.3.0+)
  * [PacketEvents](https://github.com/retrooper/packetevents) (v2.13.0+)

---

## 🚀 Installation & Setup

1. Download or compile `MeteorAntiESP.jar` via GitHub Actions.
2. Ensure both **ProtocolLib** and **PacketEvents** are installed in your server's `plugins/` directory.
3. Drop `MeteorAntiESP.jar` into the `plugins/` directory.
4. Start or restart your server to generate the default `config.yml`.
5. Tune your configuration options as needed and execute `/antifreecam reload`.

---

## ⚙️ Configuration (`config.yml`)


# ==========================================
# MeteorSMP AntiESP & AntiFreecam Config
# ==========================================

debug: false

# AntiFreecam settings
hideBelowY: 55
revealBelowYWhenUnder: 50
lazyUnmaskDistance: 64
maskEntities: true
modDetection: true

# Anti-ESP settings
check-interval: 2
max-distance: 64.0
min-distance: 5.0

# Team exemption
team-exemption:
  enabled: true
  detection-method: "scoreboard"

# Transparent block list (ignored during line-of-sight checks)
transparent-blocks:
  custom:
    - GLASS
    - GLASS_PANE
    - IRON_BARS
    - CHAIN
    - LADDER
    - VINE
    - WATER
    - LAVA
    - TORCH

# Messages
messages:
  plugin-enabled: "&aMeteorAntiESP & AntiFreecam plugin has been enabled!"
  plugin-disabled: "&cMeteorAntiESP plugin has been disabled!"
  config-reloaded: "&aConfiguration reloaded successfully!"
  no-permission: "&cYou do not have permission to use this command!"
  bypass-enabled: "&aBypass has been &2ENABLED&a."
  bypass-disabled: "&cBypass has been &4DISABLED&c."
