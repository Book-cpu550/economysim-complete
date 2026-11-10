# EconomySim Complete — NVIDIA NIM edition

A client-side economy dashboard and add-on bundle for Minecraft Java 26.2.

> **100% AI coded.** This disclosure applies to the EconomySim components. Fabric API and ShulkerBoxTooltip are separate third-party dependencies. AI-generated ratings, forecasts, and deal suggestions can be wrong.

## Install

Use Minecraft Java **26.2**, **Java 25**, and **Fabric Loader 0.19.5 or later**. Build this branch or download the JAR artifact from its **Build NVIDIA NIM edition** GitHub Actions run. Put `economysim-complete-1.0.1-nim+mc26.2.jar` in your client's `mods` folder in place of the original EconomySim bundle. Install [Fabric API for Minecraft 26.2](https://modrinth.com/mod/fabric-api/versions?g=26.2) and [ShulkerBoxTooltip 5.4.1+26.2](https://github.com/MisterPeModder/ShulkerBoxTooltip/releases) separately. Remove standalone copies of the EconomySim add-ons. Open the dashboard with `/economysim`.

Version 1.0.1 stores Item Rater memory, saved predictions, and Advisor deal notes by server profile. Previous shared files are preserved and aren’t assigned automatically to a server.

## NVIDIA-hosted AI setup

Item Rater, Predicted Prices, Loan Advisor, and Advisor all use the same NVIDIA API key and model. Requests go directly from the Minecraft client to `https://integrate.api.nvidia.com/v1/chat/completions`. No Ollama, local model, proxy process, or Hermes connection is required.

1. Choose a hosted chat/instruct model at [NVIDIA's API catalog](https://build.nvidia.com/), get an API key, and copy the exact model ID shown in its API example. Choose a model with enough context for your market snapshots and JSON-object output support for ratings and prices.
2. While playing, use the existing client command `/advisor key <your-NVIDIA-key>`, or copy the key to your clipboard and run `/advisor key paste`.
3. Run `/advisor model <exact-NVIDIA-model-id>`, then `/advisor status`. These settings apply to all four AI features immediately. No model is hardcoded.
4. Use `/advisor key clear` to remove the saved key. If `NVIDIA_API_KEY` is set in Minecraft's environment, that key remains available as a fallback.

Commands save shared settings in `config/economysim-advisor.json`. The saved key takes precedence over `NVIDIA_API_KEY`; `ANTHROPIC_API_KEY` is no longer used. Key commands are registered as Fabric client commands, as in the original release. Prefer `/advisor key paste` so the credential itself is not entered in the command line. The key is stored as local plaintext, with owner-only file permissions where supported; keep that file private.

Advanced settings (edit while Minecraft is closed) are shown in [the example configuration](examples/economysim-advisor.json): `timeoutSeconds` defaults to 120 (1–300 allowed), `maxTokens` to 900 for Advisor, and `historyTurns` to 4. The other modules keep their original output limits. `jsonMode` defaults to true; set it to false for a model without NVIDIA's `json_object` response format. JSON prompts and output validation remain enabled either way. Reasoning-heavy models can exhaust the original short output limits; use a suitable instruct model for this edition.

Old per-module Ollama endpoint/model properties are ignored. Recognized old Gemma/Claude model defaults and Anthropic/placeholder keys require new NIM setup; server profiles, loans, notes, ratings, and predictions retain their original storage behavior. Model input includes the feature's existing market, item, loan, and conversation context, now sent to NVIDIA. Rate limits and service errors are surfaced without automatically retrying a potentially billed request. The dashboard and offline deal math remain available without an API key.

## Build and validation

Install **JDK 25 or newer**, **Python 3.10+**, and **curl**, then run:

```sh
python3 build.py --test
```

The build downloads the original v1.0.1 release and checksum-pinned build dependencies, compiles the new transport/configuration code, patches only the four AI modules, verifies patched bytecode, and writes `dist/economysim-complete-1.0.1-nim+mc26.2.jar`. The published upstream repository contains no Java source; this branch is a reproducible patch project, not a recovered full source tree. Build tools, test stubs, and downloaded dependencies are not bundled into the mod. Minecraft supplies Gson and Fabric Loader at runtime.

Tests use a local mock HTTP server and fake credentials. They cover both request/response formats, ratings and prediction JSON, shared settings, authentication, errors, timeouts, cancellation, archive integrity, preserved resources, and unchanged unrelated modules. No real API key is needed for the build or CI.

Before using this edition regularly, launch Minecraft with the required dependencies and smoke-test Item Rater analysis/chat, price predictions, Loan Advisor, and Advisor using your chosen NVIDIA model. Confirm switching server profiles preserves their separate data. In-game behavior and live NVIDIA responses require this additional validation; standalone tests do not cover them. To revert, replace the NIM JAR with the original v1.0.1 JAR and restore your previous advisor configuration if needed.

## Included mods and commands

A dash indicates there is no standalone Minecraft chat command registered by that component. Open the dashboard to find its tabs and controls.

| Mod | What it does | Minecraft commands or controls |
| --- | --- | --- |
| EconomySim 1.2.2 | Balance, market history, loans, and tracked item prices. | `/economysim`, `/economysim balance <amount>`, `/loan add <amount> <rate> <player>`, `/loan list`, `/loan remove <id>`, `/additemprice <item> <amount>`, `/priorityitem <item>` |
| Advisor 1.0.0 | AI deal advice using market data and saved deal notes. | `/advisor status`, `/advisor model <name>`, `/advisor key <value>`, `/advisor key paste`, `/advisor key clear` |
| Cooldown Widget 1.0.8 | Shows cooldowns, mining earnings, and mob HP. | F9 by default |
| Deal Targets 1.0.7 | Sets buy and resale targets and values shulker bundles. | — |
| DWL 1.0.3 | Deal calculator, watchlist, and market replay. | — |
| Item Rater 1.0.0 | Rates gear from item lore and discusses combos. | — |
| Liquidity 1.1.3 | Adds listing-based liquidity scores to the bargain overlay. | — |
| Loan Manager 1.0.1 | Tracks loans, repayments, collateral, and due dates. | Loans tab |
| Market Tools 1.1.0 | Adds market warnings, trends, a trade journal, and server profiles. | — |
| MEV 1.1.0 | Simulates market events using tracked prices and listings. | MEV button in the Auction House view |
| News Widget 1.0.0 | Shows economy news in a compact HUD. | F8 by default |
| Predicted Prices 1.3.5 | Estimates prices and finds deals across markets. | `/marketdeals`. Inside the pricing chat only, use `/list`, `/forget <item>`, or `/clearnotes`. |
| Shulker Bridge 1.0.3 | Uses shulker previews to estimate bundle bargains. | — |

## Required dependencies (install separately)

- **Fabric API** for Minecraft 26.2. Tested with `0.161.0+26.2`. [Fabric API downloads](https://modrinth.com/mod/fabric-api/versions?g=26.2)
- **ShulkerBoxTooltip** Fabric version `5.4.1+26.2`, required by the Shulker Bridge. [ShulkerBoxTooltip releases](https://github.com/MisterPeModder/ShulkerBoxTooltip/releases)
