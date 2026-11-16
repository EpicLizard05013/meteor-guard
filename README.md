# MeteorGuard

A Paper 1.21.x anti-cheat / anti-ESP plugin focused on freecam, xray and client-mod abuse, with low overhead.

## Features

- **AntiFreecam (always on, no switch)** - surface players get deepslate where the deep caves are, players underground get stone where the surface is. Mobs, items and armor stands inside the hidden area are hidden too. Built on PacketEvents.
- **Anti-ESP** - players behind walls are hidden (they stay in the tab list); chests, spawners, furnaces, shulkers... are hidden from far-away players.
- **Stash-entity hiding** - dropped items, item frames, chest minecarts and armor stands behind walls are hidden (item/stash ESP).
- **Anti plugin-scan** - `/pl`, `/ver`, namespaced commands and tab-complete scans (Meteor Client) see nothing; `/meteor` looks like an unknown command to players without permission.
- **Client checks** - blocked cheat-client brands (alert or kick) and "vanilla" brands that registered Fabric/Forge channels.
- **Lag protection** - auto-throttles itself when TPS drops, caps mobs per chunk, optional timed clear of dropped items, `/meteor perf` report.
- **Discord alerts** - alerts can also go to a Discord webhook.
- **Packet limiter** - drops packet floods (crash attempts) and kicks extreme ones.
- **Auto punishments** - optional console commands when a player triggers the same alert repeatedly.
- Join protection (bot/IP limits, attack mode, verification), rate limits, illegal item scanning, ore-ratio alerts, auto-clicker and build-macro checks, GrimAC link, staff alerts with log files.

## Requirements

- Paper or Purpur 1.21.x, Java 21
- **PacketEvents 2.13.0+** plugin (required - AntiFreecam needs it)
- Optional: GrimAC, LuckPerms, Floodgate. ProtocolLib is not needed by this plugin.

## Commands

`/meteor` or `/meteorguard` - everything is a subcommand of these two (same command, either name).

| Command | What it does |
|---|---|
| `/meteor control` | live control room GUI |
| `/meteor status` | overall anti-ESP status |
| `/meteor antiesp [status\|toggle\|bypass [player]]` | anti-ESP controls |
| `/meteor antifreecam` | AntiFreecam status (always on) |
| `/meteor antifreecam bypass [player]` | toggle bypass for a player |
| `/meteor antifreecam refresh [player]` | re-check the layer and re-send chunks |
| `/meteor perf [clear]` | TPS and lag report; `clear` removes old dropped items |
| `/meteor inspect <player>` | client brand, ping, modded channels, AntiFreecam layer |
| `/meteor reload` | reload config.yml |
| `/meteor toggle` | same as `/meteor antiesp toggle` (AntiFreecam stays on) |
| `/meteor alerts` | toggle your staff alerts |
| `/meteor attack [on/off]` | force join-attack mode |
| `/meteor scan <player>` | scan a player's items |

## Building

```
gradle build
```
The jar is in `build/libs/`.
