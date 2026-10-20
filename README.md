# QuestRank

QuestRank is a NeoForge mod that publishes FTB Quests progress as a server-authoritative
ranking for group teams and individual players.

## Features

- Ranking of party teams and solo players from live FTB data.
- Server-side calculation; clients only present the received snapshot.
- `C / N` and exact integer percentage per participant.
- Sidebar entry in FTB Library, with a configurable keyboard fallback.
- Support for FTB Quests catalog reloads, team changes and administrative progress resets.
- Bounded, asynchronous player-skin cache using the vanilla client API.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 |
| FTB Library | 2101.1.37 |
| FTB Quests | 2101.1.36 |
| FTB Teams | 2101.1.11 |
| Architectury | 13.0.11 |
| Java | 21 |

Versions outside this set are not verified.

## Installation

Install QuestRank and its required FTB dependencies on both the server and the client.
The ranking is opened from the FTB Library sidebar button or the configured key.

## Build and test

```text
.\gradlew.bat build
.\gradlew.bat test
```

## Compatibility notes

- The compatibility target is the fixed version set above.
- CustomSkinLoader is not a dependency. CSL 15.1 with an Ely.by skin was verified
  manually; other CSL sources and premium-account skins are not exhaustively verified.
- The 100-participant scale test used a synthetic fixture. Measured payload was
  6,798 bytes (body before compression); 1 MiB enforcement was not demonstrated.
- FTB Quests administrative resets and team merge/split semantics are reflected as-is;
  QuestRank does not compensate or reinterpret them.

## License

QuestRank is licensed under the MIT License. See [LICENSE](LICENSE).

Files originating from the NeoForge MDK retain their separate MIT attribution in
[TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).
