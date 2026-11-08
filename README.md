# EconomySim Complete

A client-side economy dashboard and add-on bundle for Minecraft Java 26.2.

> **100% AI coded.** This disclosure applies to the EconomySim components. Fabric API and ShulkerBoxTooltip are separate third-party dependencies. AI-generated ratings, forecasts, and deal suggestions can be wrong.

## Install

Use Minecraft Java **26.2**, **Java 25**, and **Fabric Loader 0.19.5 or later**. Download `economysim-complete-1.0.1+mc26.2.jar` from Releases and put it in your client’s `mods` folder. Install both dependencies below separately in the same `mods` folder. Remove any standalone copies of the EconomySim add-ons. Open the dashboard with `/economysim`.

Version 1.0.1 stores Item Rater memory, saved predictions, and Advisor deal notes by server profile. Previous shared files are preserved and aren’t assigned automatically to a server.

## Local AI setup

**Setup disclaimer.** For the Hermes workflow, you need an existing Hermes Agent connection to a running Ollama server. Select `gemma4:12b` as the model on that connection. EconomySim itself calls Ollama directly on `http://localhost:11434`, so Hermes is optional for the mod and is only required for the Hermes workflow. The core dashboard works without a local model.

1. Install and start Ollama on the computer running Minecraft. Run `ollama pull gemma4:12b`, then `ollama run gemma4:12b` to verify the model works.
2. For Hermes, use `hermes model`, select a custom Ollama endpoint at `http://localhost:11434/v1`, and choose `gemma4:12b`. Configure Ollama and Hermes for the same context length. Hermes tool use requires at least 64,000 tokens. [Hermes setup guide](https://hermes-agent.nousresearch.com/docs/guides/local-ollama-setup)
3. Item Rater, Predicted Prices, and Loan Manager default to `gemma4:12b`. For the separate Advisor tab, set `/advisor model gemma4:12b`. This build also checks for a nonempty key before enabling the AI tab. For stock local Ollama, which requires no authentication, use a placeholder such as `/advisor key ollama-local-placeholder`. [Ollama local authentication](https://docs.ollama.com/api/authentication)

### Gemma 4 12B hardware

- **Disk** — The Ollama model download is about **7.7 GB**. Allow **10 GB or more** of free space for the model and working room. [Ollama model listing](https://ollama.com/library/gemma4:12b)
- **RAM and GPU** — For Minecraft plus local inference, **32 GB system RAM** and an Ollama-supported GPU with **12–16 GB VRAM** are practical starting targets for shorter prompts. CPU inference is possible with sufficient system RAM and runs more slowly. Google estimates **6.7 GB** to load its Q4_0 variant, before planning for the specific Ollama build, longer contexts, and Minecraft. [Google memory estimates](https://ai.google.dev/gemma/docs/core) · [Ollama GPU support](https://docs.ollama.com/gpu)
- **64K context** — The Advisor requests **65,536 tokens** when preloading Gemma, and Hermes also needs a long context for tool use. Plan for more memory, with **24 GB VRAM** as a practical starting target for GPU use at that length. These are estimates, and actual use depends on quantization, context settings, and available memory. Check the effective context and CPU/GPU split with `ollama ps`. [Ollama context length](https://docs.ollama.com/context-length)

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
