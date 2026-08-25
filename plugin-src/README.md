# CustomBlockGUI Plugin

Paper 1.21.1 plugin - Custom block with custom GUI.

## Features
- `/givecustomblock` gives a SMITHING_TABLE with custom NBT (CustomModelData 1001)
- Place it -> becomes tracked custom block
- Right-click -> opens GUI:
  - Slot 11: Storage (27 slots per-block, persistent save to `plugins/CustomBlockGUI/customblocks.yml`)
  - Slot 13: Craft demo (gives diamond)
  - Slot 15: Info
  - Slot 22: Close
- Storage GUI is per-block, persists across restarts, drops items on break
- Break block -> drops stored items + custom item
- Shift + right-click with block in hand bypasses GUI to allow placing against it

## Edit
- Change block type: `CustomBlockPlugin.java:18` -> `CUSTOM_BLOCK_MATERIAL`
- Edit GUI layout: `GUIManager.java:59` (`openMainGUI`)
- Add resource pack: set `CustomModelData 1001` in meta
- Commands: `/cb info`, `/cb list`, `/cb removeall`

## Build
```bash
cd plugin-src/CustomBlockGUI
mvn package
cp target/CustomBlockGUI-1.0.0.jar ../../plugins/
```
Workflow `server.yml` auto-builds on GitHub Actions.
