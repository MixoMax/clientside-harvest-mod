# Client Harvest

A small Fabric client-side mod that makes harvesting and replanting crops faster by removing the need to break and right-click separately. Works on any vanilla-compatible server — no server-side installation required.

## What it does

Right-clicking a fully grown crop breaks it and replants it in a single action, as long as you have the right seed available. The mod figures out which seed matches which crop automatically, including modded crops that extend vanilla's `CropBlock`.

Supported crops: wheat, carrots, potatoes, beetroot, torchflower, pitcher plant, cocoa beans, nether wart.

## Usage

Hold the matching seed in your main hand (or offhand) and right-click any fully grown crop. That's it.

If you have a Fortune-enchanted tool in your main hand, the mod will use it to break the crop for better drops while still replanting. It will automatically move the matching seed to your offhand if it finds one in your hotbar, so the Fortune tool stays in your main hand throughout.

**Seed placement priority:**
- If a matching seed (e.g. wheat seeds for wheat, carrot for carrot farm) is anywhere in your hotbar or offhand, that takes priority over a generic seed
- Matching seed + Fortune tool: seed moves to offhand, Fortune tool breaks the crop, replant happens automatically
- Matching seed, no Fortune: hotbar switches to the seed slot, break and replant
- No matching seed but any seed in offhand + Fortune tool: breaks with Fortune and replants with whatever seed is there
- No seed anywhere: just breaks the crop, no replant

## Toggle

Press **G** to enable or disable the mod. A message will appear on screen confirming the current state. The setting is saved between sessions.

The keybind can be rebound in Minecraft's controls menu under the *Client Harvest* category.

## Who is this for

Anyone who spends time maintaining large crop farms in survival multiplayer. The vanilla workflow of breaking a crop and separately right-clicking to replant is fine for small farms, but gets tedious at scale. This mod streamlines that without touching anything server-side, so it works anywhere you would normally play.

## Installation

Requires [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api). Drop the mod jar into your `mods` folder.