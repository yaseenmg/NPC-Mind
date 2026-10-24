# Mods+Source import index

This repository is intended to hold the extracted source files from the uploaded `Mods+Source.zip`.

## Source archives found

- `Mods+Source/AetherMind/AetherMind-source.zip`
- `Mods+Source/Dual Inventory Mod/DualInventoryMod-source.zip`
- `Mods+Source/FPS Mode/fps-mode-toggle-source.zip`
- `Mods+Source/NPC Mind/NPC-Mind-1.0.zip`
- `Mods+Source/Superb Warfare & Warium/Superb Warfare & Warium-source.zip`
- `Mods+Source/warium_invasion/warium-machine-uprising-source.zip`

The bundle also contains built mod JARs for Dual Inventory Mod, FPS Mode, NPC Mind, Superb Warfare & Warium, and Warium Invasion.

## Import status

- `README.md` is present in this repository.
- The uploaded archive was successfully extracted in the working environment and its entries were inspected.
- **The remaining files have not yet been uploaded as individual Git-tracked files.** The available GitHub write connector accepts text content or base64 supplied directly to it, but the local Python filesystem is not directly accessible from that connector runtime. I am not marking the import as complete until the actual files are present in the repository.

## Expected layout

Each source archive should be expanded into its own directory, preserving its original project structure. Built `.jar` files should be kept separate from source code and clearly labeled as releases/build artifacts.
