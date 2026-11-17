# MeteorGuard

A Paper 1.21.x anti-cheat / anti-ESP plugin focused on freecam, xray, and client-mod abuse, with low overhead.

## Features

- **AntiFreecam (always on, no switch)** - surface players get deepslate where the deep caves are, players underground get stone where the surface is. Mobs, items, and armor stands inside the hidden area are hidden too. Built on PacketEvents.
- **Anti-ESP** - players behind walls are hidden (they stay in the tab list); chests, spawners, furnaces, shulkers, and other containers are hidden from far-away players.
- **Stash-entity hiding** - dropped items, item frames, chest minecarts, and armor stands behind walls are hidden (item/stash ESP).
- **Suspicious Ore Log & Chest GUI** - players who repeatedly trigger rare-ore ratio or burst checks (`OreWatch`) are logged persistently to `plugins/MeteorGuard/data/suspicious.yml` and can be reviewed in a paginated 54-slot chest GUI (`/suspicious` or `/meteorguard suspicious`) showing player heads, ore breakdowns, ratios, coordinates, teleport, inspect, and log removal.
- **Anti plugin-scan** - `/pl`, `/ver`, namespaced commands, and tab-complete scans (Meteor Client) see nothing; `/meteor`, `/meteorguard`, and `/suspicious` are completely hidden and look like unknown commands to players without the required rank/permission.
- **Client checks** - blocked cheat-client brands (alert or kick) and "vanilla" brands that registered Fabric/Forge channels.
- **Lag protection** - auto-throttles itself when TPS drops, caps mobs per chunk, optional timed clear of dropped items, `/meteor perf` report.
- **Discord alerts** - alerts can also go to a Discord webhook.
- **Packet limiter** - drops packet floods (crash attempts) and kicks extreme ones.
- **Auto punishments** - optional console commands when a player triggers the same alert repeatedly.
- Join protection (bot/IP limits, attack mode, verification), rate limits, illegal item scanning, ore-ratio alerts, auto-clicker and build-macro checks, GrimAC link, and staff alerts with log files.

## Requirements

- Paper or Purpur 1.21.x, Java 21
- **PacketEvents 2.13.0+** plugin (required - AntiFreecam and packet limiting need it)
- Optional: GrimAC, LuckPerms, Floodgate. ProtocolLib is not needed by this plugin.

## Commands

`/meteor` or `/meteorguard` - main command (same command, either name).  
`/suspicious` - standalone shortcut to open the suspicious ore log GUI.

| Command | What it does |
|---|---|
| `/meteor control` | live control room GUI |
| `/meteor status` | overall anti-ESP status |
| `/meteor antiesp [status\|toggle\|bypass [player]]` | anti-ESP controls |
| `/meteor antifreecam` | AntiFreecam status (always on) |
| `/meteor antifreecam bypass [player]` | toggle bypass for a player |
| `/meteor antifreecam refresh [player]` | re-check the layer and re-send chunks |
| `/meteor suspicious` / `/suspicious` | open the paginated chest GUI of suspicious rare-ore miners |
| `/meteor perf [clear]` | TPS and lag report; `clear` removes old dropped items |
| `/meteor inspect <player>` | client brand, ping, modded channels, AntiFreecam layer |
| `/meteor reload` | reload `config.yml` |
| `/meteor toggle` | same as `/meteor antiesp toggle` (AntiFreecam stays on) |
| `/meteor alerts` | toggle your staff alerts |
| `/meteor attack [on\|off]` | force join-attack mode |
| `/meteor scan <player>` | scan a player's items |

### Suspicious GUI Controls
- **Left-Click** a player head: teleport to the player (or their last flagged mining coordinates if offline)
- **Right-Click** a player head: inspect the player (brand, ping, modded channels, AntiFreecam layer)
- **Shift + Right-Click** a player head: remove the player from the suspicious log
- **Arrows / Clock** (bottom bar): navigate pages and refresh the list

## Permissions

| Permission | Default | Description |
|---|---|---|
| `meteorguard.admin` | `op` | Full access to all `/meteor` and `/suspicious` commands |
| `meteorguard.suspicious` | `op` | Access to `/suspicious` and `/meteorguard suspicious` (hidden from lower ranks) |
| `meteorshield.alerts` | `op` | Receive staff alerts and use `/meteor alerts` |
| `meteorguard.seecommands` | `op` | See hidden/namespaced commands in tab-complete and use blocked plugin-list commands |
| `antifreecam.admin` | `op` | Legacy alias for admin access |
| `antiesp.bypass` | `false` | Bypass player/container/entity ESP hiding |
| `antifreecam.bypass` | `false` | Bypass AntiFreecam chunk masking |
| `meteorguard.bypass` | `false` | Bypass autoclicker and build-macro checks |
| `meteorshield.bypass` | `false` | Bypass item sanity, ore watch, rate limits, brand checks, and verification |

## Building

```bash
gradle build
