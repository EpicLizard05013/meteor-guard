# MeteorAntiESP

A lightweight Paper/Purpur anti-cheat plugin for Minecraft 1.21.11 designed to block common ESP, wallhack, freecam, and underground X-ray style behavior while preserving normal server performance.

This project is built in Java and focuses on low-overhead checks, staff controls, join protection, item sanity scanning, and anti-freecam shielding.

## Features

- Anti-ESP / line-of-sight occlusion checks for players and hidden rendering
- AntiFreecam protection and subterranean masking behavior
- Container hiding for chests, spawners, furnaces, shulkers, and similar blocks
- Staff alerts and logging with configurable cooldowns
- Join protection against bot-style attacks, IP abuse, and suspicious names
- Rate limiting for chat, commands, inventory clicks, and interaction spam
- Illegal item detection and cleanup/removal options
- Ore-ratio monitoring for mining anomalies
- GrimAC integration support
- Lightweight async processing designed for low-end and high-traffic servers

## Requirements

- Server software: Paper or Purpur 1.21.11
- Java: 21
- Dependencies:
  - [ProtocolLib](https://github.com/dmulloy2/ProtocolLib)
  - [PacketEvents](https://github.com/retrooper/packetevents)

## Installation

1. Download the plugin jar from the Releases section or build it locally.
2. Place both required dependencies in your server's `plugins/` folder.
3. Drop `MeteorAntiESP.jar` into the same `plugins/` directory.
4. Start or restart the server.
5. The plugin will generate its default `config.yml` on startup.
6. Adjust settings in `plugins/MeteorAntiESP/config.yml` as needed.
7. Reload with `/antifreecam reload`.

## Commands

The plugin registers the following command:

- `/antifreecam` or `/antiesp` or `/shield`

Subcommands:

- `reload`
- `toggle`
- `status`
- `bypass`
- `alerts`
- `attack`
- `scan`

Example:

```bash
/antifreecam reload
/antifreecam status
/antifreecam bypass
```

## Permissions

- `antifreecam.admin`
- `antifreecam.bypass`
- `antiesp.bypass`
- `meteorshield.alerts`
- `meteorshield.bypass`

## Main Plugin Details

- Plugin name: `MeteorAntiESP`
- Main class: `com.meteorsmp.antiesp.AntiESPPlugin`
- API version: `1.21`
- Version in `plugin.yml`: `3.0.0`

## Configuration Highlights

The plugin supports a wide set of configurable systems, including:

- world enable/disable lists
- block occlusion and transparent-block rules
- anti-freecam behavior thresholds
- join protection limits and attack-mode logic
- chat, command, and interaction rate limits
- item checks and banned materials
- ore watch thresholds
- alert formatting and logging

See `src/main/resources/config.yml` for the full default configuration.

## Notes

This project is tuned for servers that want strong anti-cheat protection without sacrificing performance or causing excessive false positives. It is intended for Paper/Purpur environments and is designed to work with modern Paper 1.21 APIs.

## License

This project is distributed under the repository's existing license terms. If no license file is present, check with the repository owner before redistributing or using the code in a public or commercial setting.
