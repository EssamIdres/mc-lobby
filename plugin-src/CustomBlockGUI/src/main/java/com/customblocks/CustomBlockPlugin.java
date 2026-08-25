package com.customblocks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CustomBlockPlugin extends JavaPlugin {

    private static CustomBlockPlugin instance;
    public NamespacedKey customBlockKey;
    public NamespacedKey customBlockTypeKey;

    // Track custom block locations as "world:x:y:z" -> typeId
    private final Map<String, String> blockTypes = new HashMap<>();
    private final Set<String> customBlocks = new HashSet<>(); // derived for fast check, also legacy
    // Per-block storage: locationKey -> ItemStack[27]
    private final Map<String, ItemStack[]> blockStorages = new HashMap<>();
    // Display entities per block (for placed texture)
    private final Map<String, UUID> displayEntities = new HashMap<>();
    // Rotation per block (yaw degrees)
    private final Map<String, Float> blockRotations = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    // === CUSTOM BLOCK DEFINITIONS ===
    // Each DiscoveryLab block gets its own CustomModelData on same base material
    // This works with DiscoveryLab-pack resource pack (new vanilla way, no Oraxen)
    // Base material = SMITHING_TABLE (distinct, no vanilla GUI conflict after we cancel)
    // You can change per-type material if you want different base.
    public static class BlockType {
        public final String id;
        public final Material material;
        public final int customModelData;
        public final String displayName;
        public final String description;
        public BlockType(String id, Material material, int cmd, String displayName, String description) {
            this.id = id; this.material = material; this.customModelData = cmd; this.displayName = displayName; this.description = description;
        }
    }

    public static final Map<String, BlockType> BLOCK_TYPES = new LinkedHashMap<>();
    static {
        // DiscoveryLab 14 blocks + default
        BLOCK_TYPES.put("altar", new BlockType("altar", Material.SMITHING_TABLE, 1001, "§d§lAltar", "§7Mystic crafting altar"));
        BLOCK_TYPES.put("autocrafter", new BlockType("autocrafter", Material.SMITHING_TABLE, 1002, "§6§lAutocrafter", "§7Automatic crafting"));
        BLOCK_TYPES.put("belt", new BlockType("belt", Material.SMITHING_TABLE, 1003, "§e§lBelt Machine", "§7Conveyor belt (animated)"));
        BLOCK_TYPES.put("belt_machine", new BlockType("belt_machine", Material.SMITHING_TABLE, 1003, "§e§lBelt Machine", "§7Conveyor belt (animated)"));
        BLOCK_TYPES.put("crusher", new BlockType("crusher", Material.SMITHING_TABLE, 1004, "§8§lCrusher", "§7Crushes ores to dust"));
        BLOCK_TYPES.put("drill", new BlockType("drill", Material.SMITHING_TABLE, 1005, "§7§lDrill", "§7Mines deep"));
        BLOCK_TYPES.put("druglab", new BlockType("druglab", Material.SMITHING_TABLE, 1006, "§5§lDrug Lab", "§7Chemical station"));
        BLOCK_TYPES.put("generator", new BlockType("generator", Material.SMITHING_TABLE, 1007, "§c§lGenerator", "§7Generates power"));
        BLOCK_TYPES.put("packaging", new BlockType("packaging", Material.SMITHING_TABLE, 1008, "§a§lPackaging Machine", "§7Packs items"));
        BLOCK_TYPES.put("pipe", new BlockType("pipe", Material.SMITHING_TABLE, 1009, "§f§lPipe", "§7Transports items/fluids"));
        BLOCK_TYPES.put("press", new BlockType("press", Material.SMITHING_TABLE, 1010, "§6§lPress", "§7Presses materials"));
        BLOCK_TYPES.put("sorter", new BlockType("sorter", Material.SMITHING_TABLE, 1011, "§9§lSorter", "§7Sorts items"));
        BLOCK_TYPES.put("spawnercore", new BlockType("spawnercore", Material.SMITHING_TABLE, 1012, "§5§lSpawner Core", "§7Spawner upgrade"));
        BLOCK_TYPES.put("splitter", new BlockType("splitter", Material.SMITHING_TABLE, 1013, "§b§lSplitter", "§7Splits conveyor"));
        BLOCK_TYPES.put("totem_machine", new BlockType("totem_machine", Material.SMITHING_TABLE, 1014, "§a§lTotem Machine", "§7Totem crafting"));
        BLOCK_TYPES.put("copper_forge", new BlockType("copper_forge", Material.SMITHING_TABLE, 1015, "§6§lCopper Forge", "§7Forge for copper"));
        // fallback/default
        BLOCK_TYPES.put("machine", new BlockType("machine", Material.SMITHING_TABLE, 1001, "§6§lCustom Machine", "§7Generic custom block"));
    }

    // Legacy single material for backwards compat (now we use per-type)
    public static final Material CUSTOM_BLOCK_MATERIAL = Material.SMITHING_TABLE;

    @Override
    public void onEnable() {
        instance = this;
        customBlockKey = new NamespacedKey(this, "custom_block");
        customBlockTypeKey = new NamespacedKey(this, "custom_block_type");

        try { saveDefaultConfig(); } catch (Exception ignored) {}
        setupDataFile();
        loadCustomBlocks();

        // Register listeners
        getServer().getPluginManager().registerEvents(new CustomBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIManager(this), this);

        // Register command
        GiveCommand giveCommand = new GiveCommand(this);
        Objects.requireNonNull(getCommand("givecustomblock")).setExecutor(giveCommand);
        Objects.requireNonNull(getCommand("givecustomblock")).setTabCompleter(giveCommand);
        Objects.requireNonNull(getCommand("customblock")).setExecutor(giveCommand);
        Objects.requireNonNull(getCommand("customblock")).setTabCompleter(giveCommand);

        // Register 15 shaped recipes (DiscoveryLab - survival craftable)
        registerRecipes();

        // Respawn display entities for placed texture (ItemDisplay) - was 40 ticks (too slow), now 20 ticks (1s)
        Bukkit.getScheduler().runTaskLater(this, this::respawnAllDisplays, 20L);

        getLogger().info("CustomBlockGUI v1.3.1 enabled! " + BLOCK_TYPES.size() + " custom blocks: " + String.join(", ", BLOCK_TYPES.keySet()));
        getLogger().info("Use /givecustomblock <type> or /givecustomblock * | Textures via DiscoveryLab-pack (new way, no Oraxen)");
        getLogger().info("Recipes registered for all 15 machines - craft in survival!");
        getLogger().info("Per-block GUI features: crusher, drill, generator, etc. - shift+block to place, no vanilla smithing GUI");
        getLogger().info("Placed blocks use ItemDisplay scale 2.5 BARRIER+display for full size");
    }

    @Override
    public void onDisable() {
        if (dataConfig != null && dataFile != null) {
            saveCustomBlocks();
            getLogger().info("CustomBlockGUI disabled, saved " + blockTypes.size() + " blocks");
        }
        // Optionally keep displays persistent across restart? We remove and respawn on enable.
        // Don't remove here to keep them saved in world, but we also respawn check.
    }

    public static CustomBlockPlugin getInstance() {
        return instance;
    }

    public Collection<BlockType> getAllBlockTypes() { return BLOCK_TYPES.values(); }
    public BlockType getBlockTypeDef(String id) {
        if (id == null) return BLOCK_TYPES.get("machine");
        BlockType t = BLOCK_TYPES.get(id.toLowerCase());
        return t != null ? t : BLOCK_TYPES.get("machine");
    }

    // === ITEM CREATION ===
    public ItemStack createCustomBlockItem(String typeId, int amount) {
        BlockType type = getBlockTypeDef(typeId);
        ItemStack item = new ItemStack(type.material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(type.displayName + " §7(Right-click)"));
            meta.lore(List.of(
                    net.kyori.adventure.text.Component.text(type.description),
                    net.kyori.adventure.text.Component.text("§7Custom texture §f#" + type.customModelData),
                    net.kyori.adventure.text.Component.text(""),
                    net.kyori.adventure.text.Component.text("§e► Place on ground"),
                    net.kyori.adventure.text.Component.text("§e► Right-click to open GUI"),
                    net.kyori.adventure.text.Component.text("§e► Shift+Right-click to place against"),
                    net.kyori.adventure.text.Component.text(""),
                    net.kyori.adventure.text.Component.text("§8DiscoveryLab §7| §f1.21.1 §8(" + type.id + ")")
            ));
            meta.getPersistentDataContainer().set(customBlockKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(customBlockTypeKey, PersistentDataType.STRING, type.id);
            meta.setEnchantmentGlintOverride(false);
            meta.setCustomModelData(type.customModelData);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createCustomBlockItem(int amount) {
        return createCustomBlockItem("machine", amount);
    }

    public boolean isCustomBlockItem(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        // Any material that is in BLOCK_TYPES with our PDC is custom
        return meta.getPersistentDataContainer().has(customBlockKey, PersistentDataType.BYTE);
    }

    public String getTypeFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "machine";
        String t = item.getItemMeta().getPersistentDataContainer().get(customBlockTypeKey, PersistentDataType.STRING);
        return t != null ? t : "machine";
    }

    // === LOCATION HANDLING ===
    public String locToKey(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public String locToKey(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ":" + y + ":" + z;
    }

    public Location keyToLoc(String key) {
        try {
            String[] parts = key.split(":");
            UUID worldId = UUID.fromString(parts[0]);
            World w = Bukkit.getWorld(worldId);
            if (w == null) return null;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new Location(w, x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isCustomBlock(Location loc) {
        return blockTypes.containsKey(locToKey(loc)) || customBlocks.contains(locToKey(loc));
    }

    public String getBlockTypeAt(Location loc) {
        String key = locToKey(loc);
        String t = blockTypes.get(key);
        if (t != null) return t;
        // legacy fallback
        if (customBlocks.contains(key)) return "machine";
        return null;
    }

    public void addCustomBlock(Location loc, String typeId, float yaw) {
        String key = locToKey(loc);
        String type = getBlockTypeDef(typeId).id;
        blockTypes.put(key, type);
        customBlocks.add(key);
        // Store rotation snapped to 90 degrees (0,90,180,270)
        float rot = Math.round(yaw / 90f) * 90f;
        rot = (rot % 360 + 360) % 360;
        blockRotations.put(key, rot);
        saveCustomBlocks();
    }

    public void addCustomBlock(Location loc, String typeId) {
        addCustomBlock(loc, typeId, 0f);
    }

    public void addCustomBlock(Location loc) {
        addCustomBlock(loc, "machine", 0f);
    }

    public void removeCustomBlock(Location loc) {
        String key = locToKey(loc);
        blockTypes.remove(key);
        customBlocks.remove(key);
        blockStorages.remove(key);
        blockRotations.remove(key);
        saveCustomBlocks();
    }

    public float getBlockRotation(Location loc) {
        return blockRotations.getOrDefault(locToKey(loc), 0f);
    }

    public Set<String> getCustomBlocks() {
        return customBlocks;
    }

    public Map<String, String> getBlockTypes() { return blockTypes; }

    public Map<String, ItemStack[]> getBlockStorages() {
        return blockStorages;
    }

    // === PERSISTENCE ===
    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "customblocks.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void loadCustomBlocks() {
        blockTypes.clear();
        customBlocks.clear();
        blockStorages.clear();
        List<String> list = dataConfig.getStringList("blocks");
        customBlocks.addAll(list);
        // Load typed blocks
        if (dataConfig.contains("typedBlocks")) {
            for (String key : dataConfig.getConfigurationSection("typedBlocks").getKeys(false)) {
                String type = dataConfig.getString("typedBlocks." + key);
                if (type != null) {
                    blockTypes.put(key, type);
                    customBlocks.add(key);
                }
            }
        } else {
            // Migrate legacy: all old blocks become "machine"
            for (String key : list) blockTypes.put(key, "machine");
        }
        getLogger().info("Loaded " + blockTypes.size() + " custom blocks (" + customBlocks.size() + " total)");

        // Load storages
        if (dataConfig.contains("storages")) {
            for (String key : dataConfig.getConfigurationSection("storages").getKeys(false)) {
                List<?> raw = dataConfig.getList("storages." + key);
                if (raw != null) {
                    ItemStack[] contents = new ItemStack[27];
                    for (int i = 0; i < Math.min(raw.size(), 27); i++) {
                        Object o = raw.get(i);
                        if (o instanceof ItemStack) {
                            contents[i] = (ItemStack) o;
                        }
                    }
                    blockStorages.put(key, contents);
                }
            }
            getLogger().info("Loaded " + blockStorages.size() + " storages");
        }

        // Load rotations
        if (dataConfig.contains("rotations")) {
            for (String key : dataConfig.getConfigurationSection("rotations").getKeys(false)) {
                float rot = (float) dataConfig.getDouble("rotations." + key);
                blockRotations.put(key, rot);
            }
            getLogger().info("Loaded " + blockRotations.size() + " rotations");
        }

        // Validate (remove invalid worlds)
        blockTypes.keySet().removeIf(key -> keyToLoc(key) == null);
        customBlocks.removeIf(key -> keyToLoc(key) == null);
        blockRotations.keySet().removeIf(key -> keyToLoc(key) == null);
    }

    public void saveCustomBlocks() {
        if (dataConfig == null || dataFile == null) return;
        try {
            dataConfig.set("blocks", new ArrayList<>(customBlocks));
            dataConfig.set("typedBlocks", null);
            for (Map.Entry<String, String> e : blockTypes.entrySet()) {
                dataConfig.set("typedBlocks." + e.getKey(), e.getValue());
            }
            // Save storages
            dataConfig.set("storages", null);
            for (Map.Entry<String, ItemStack[]> entry : blockStorages.entrySet()) {
                boolean empty = true;
                for (ItemStack it : entry.getValue()) if (it != null && it.getType() != Material.AIR) { empty = false; break; }
                if (!empty) {
                    dataConfig.set("storages." + entry.getKey(), Arrays.asList(entry.getValue()));
                }
            }
            dataConfig.set("rotations", null);
            for (Map.Entry<String, Float> e : blockRotations.entrySet()) {
                dataConfig.set("rotations." + e.getKey(), e.getValue());
            }
            dataConfig.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveStorage(String key, ItemStack[] contents) {
        blockStorages.put(key, contents);
        saveCustomBlocks();
    }

    public ItemStack[] getStorage(String key) {
        return blockStorages.getOrDefault(key, new ItemStack[27]);
    }

    // === DISPLAY ENTITIES (placed block texture) ===
    public void spawnDisplay(Location loc, String typeId) {
        try {
            String key = locToKey(loc);
            removeDisplay(loc);
            BlockType def = getBlockTypeDef(typeId);
            World w = loc.getWorld();
            if (w == null) return;
            Location center = loc.clone().add(0.5, 0.5, 0.5);
            // Use ItemDisplay so CustomModelData shows (BlockDisplay can't show custom)
            ItemDisplay display = (ItemDisplay) w.spawnEntity(center, EntityType.ITEM_DISPLAY);
            ItemStack item = createCustomBlockItem(typeId, 1);
            display.setItemStack(item);
            display.setBillboard(Display.Billboard.FIXED);
            display.setViewRange(64);
            display.setShadowRadius(0);
            display.setShadowStrength(0);
            try {
                // FIXED shows as item-frame-like, but with block/cube parent it renders as full block when scaled
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                // For 1.21.1 block items, FIXED with scale 1 may be small - use GUI or THIRDPERSON?
                // Try to make it block-sized: scale 1.0 is full block, but ItemDisplay with block model needs scale 1
            } catch (Exception ignored) {}
            // Brightness max so it shows even in dark
            try { display.setBrightness(new Display.Brightness(15, 15)); } catch (Exception ignored) {}
            Transformation trans = display.getTransformation();
            // Was 1.6 half size - use 2.0 per user (was 2.5 big)
            trans.getScale().set(2.0f, 2.0f, 2.0f);
            trans.getTranslation().set(0, 0, 0);
            // Rotation based on stored yaw
            float rot = blockRotations.getOrDefault(key, 0f);
            float rad = (float) Math.toRadians(-rot);
            trans.getLeftRotation().set(new Quaternionf().rotateY(rad));
            trans.getRightRotation().set(new Quaternionf());
            display.setTransformation(trans);
            display.setInterpolationDuration(0);
            display.setTeleportDuration(0);
            display.setViewRange(128);
            display.setPersistent(true);
            display.setInvulnerable(true);
            display.setCustomNameVisible(false);
            // Tag
            display.getPersistentDataContainer().set(new NamespacedKey(this, "display_loc"), PersistentDataType.STRING, key);
            display.getPersistentDataContainer().set(customBlockTypeKey, PersistentDataType.STRING, typeId);
            displayEntities.put(key, display.getUniqueId());
            getLogger().fine("Spawned display for " + typeId + " at " + key);
        } catch (Exception e) {
            getLogger().warning("Failed spawn display for " + typeId + " at " + loc + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void removeDisplay(Location loc) {
        try {
            String key = locToKey(loc);
            UUID uuid = displayEntities.remove(key);
            if (uuid != null) {
                var ent = Bukkit.getEntity(uuid);
                if (ent != null) ent.remove();
            }
            // Also scan nearby as fallback (in case UUID not tracked, e.g. after restart)
            World w = loc.getWorld();
            if (w != null) {
                Location center = loc.clone().add(0.5, 0.5, 0.5);
                for (var e : w.getNearbyEntities(center, 0.5, 0.5, 0.5)) {
                    if (e.getType() == EntityType.ITEM_DISPLAY) {
                        ItemDisplay disp = (ItemDisplay) e;
                        String locKey = disp.getPersistentDataContainer().get(new NamespacedKey(this, "display_loc"), PersistentDataType.STRING);
                        if (key.equals(locKey)) {
                            disp.remove();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void respawnAllDisplays() {
        int spawned = 0;
        int converted = 0;
        for (var entry : new HashMap<>(blockTypes).entrySet()) {
            String key = entry.getKey();
            String typeId = entry.getValue();
            Location loc = keyToLoc(key);
            if (loc == null) continue;
            World w = loc.getWorld();
            if (w == null) continue;
            // Convert old SMITHING_TABLE placed blocks to BARRIER so display shows correctly (no vanilla texture underneath)
            try {
                if (loc.getBlock().getType() == Material.SMITHING_TABLE) {
                    loc.getBlock().setType(Material.BARRIER);
                    converted++;
                }
            } catch (Exception ignored) {}
            boolean exists = false;
            Location center = loc.clone().add(0.5, 0.5, 0.5);
            for (var e : w.getNearbyEntities(center, 0.6, 0.6, 0.6)) {
                if (e.getType() == EntityType.ITEM_DISPLAY) {
                    ItemDisplay d = (ItemDisplay) e;
                    String lk = d.getPersistentDataContainer().get(new NamespacedKey(this, "display_loc"), PersistentDataType.STRING);
                    if (key.equals(lk)) { exists = true; break; }
                }
            }
            if (!exists) {
                spawnDisplay(loc, typeId);
                spawned++;
            }
        }
        if (converted > 0) getLogger().info("Converted " + converted + " old SMITHING_TABLE -> BARRIER for display");
        if (spawned > 0) getLogger().info("Respawned " + spawned + " ItemDisplay for custom blocks (placed texture)");
    }

    // === RECIPES (15 DiscoveryLab machines) ===
    private void registerRecipes() {
        // Altar: Stone + Gold + Totem
        addRecipe("altar", "SSS", "G G", "GTG", Map.of('S', Material.STONE, 'G', Material.GOLD_INGOT, 'T', Material.TOTEM_OF_UNDYING));
        // Autocrafter: Iron + Redstone + Crafting Table
        addRecipe("autocrafter", "IRI", "RCR", "IRI", Map.of('I', Material.IRON_INGOT, 'R', Material.REDSTONE, 'C', Material.CRAFTING_TABLE));
        // Belt Machine: Iron + Leather + Redstone
        addRecipe("belt_machine", "LLL", "IRI", "R R", Map.of('L', Material.LEATHER, 'I', Material.IRON_INGOT, 'R', Material.REDSTONE));
        addRecipe("belt", "LLL", "IRI", "R R", Map.of('L', Material.LEATHER, 'I', Material.IRON_INGOT, 'R', Material.REDSTONE));
        // Crusher: Iron Block + Piston + Furnace
        addRecipe("crusher", "III", "PFP", "III", Map.of('I', Material.IRON_BLOCK, 'P', Material.PISTON, 'F', Material.FURNACE));
        // Drill: Iron + Diamond + Redstone Block
        addRecipe("drill", "IDI", "IRI", "RBR", Map.of('I', Material.IRON_INGOT, 'D', Material.DIAMOND, 'R', Material.REDSTONE, 'B', Material.REDSTONE_BLOCK));
        // Drug Lab: Glass + Blaze + Brewing Stand
        addRecipe("druglab", "GGG", "B B", "S S", Map.of('G', Material.GLASS, 'B', Material.BLAZE_POWDER, 'S', Material.BREWING_STAND));
        // Generator: Furnace + Redstone + Coal Block
        addRecipe("generator", "FFF", "RCR", "FFF", Map.of('F', Material.FURNACE, 'R', Material.REDSTONE, 'C', Material.COAL_BLOCK));
        // Packaging: Paper + Iron + Chest
        addRecipe("packaging", "PPP", "ICI", "PPP", Map.of('P', Material.PAPER, 'I', Material.IRON_INGOT, 'C', Material.CHEST));
        // Pipe: Iron + Glass
        addRecipe("pipe", "IGI", "IGI", "IGI", Map.of('I', Material.IRON_INGOT, 'G', Material.GLASS));
        // Press: Iron Block + Anvil + Piston
        addRecipe("press", "III", "APA", "IPI", Map.of('I', Material.IRON_BLOCK, 'A', Material.ANVIL, 'P', Material.PISTON));
        // Sorter: Hopper + Comparator + Chest
        addRecipe("sorter", "HCH", "RCR", "HCH", Map.of('H', Material.HOPPER, 'C', Material.COMPARATOR, 'R', Material.REDSTONE));
        // Spawner Core: Iron Bars + Nether Star + Spawner (use trial spawner)
        addRecipe("spawnercore", "BBB", "BNC", "BBB", Map.of('B', Material.IRON_BARS, 'N', Material.NETHER_STAR, 'C', Material.TRIAL_SPAWNER));
        // Splitter: Iron + Redstone + Belt
        addRecipe("splitter", "IRI", "B B", "IRI", Map.of('I', Material.IRON_INGOT, 'R', Material.REDSTONE, 'B', Material.LEATHER));
        // Totem Machine: Obsidian + Emerald + Altar (use altar item as ingredient via gold block proxy)
        addRecipe("totem_machine", "OEO", "EAE", "OEO", Map.of('O', Material.OBSIDIAN, 'E', Material.EMERALD, 'A', Material.GOLD_BLOCK));
        // Copper Forge: Copper + Brick + Furnace
        addRecipe("copper_forge", "CCC", "BFB", "CCC", Map.of('C', Material.COPPER_INGOT, 'B', Material.BRICKS, 'F', Material.FURNACE));

        getLogger().info("Registered " + 16 + " recipes for DiscoveryLab machines");
    }

    private void addRecipe(String typeId, String row1, String row2, String row3, Map<Character, Material> ing) {
        try {
            BlockType type = getBlockTypeDef(typeId);
            ItemStack result = createCustomBlockItem(typeId, 1);
            NamespacedKey key = new NamespacedKey(this, "recipe_" + typeId);
            org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, result);
            recipe.shape(row1, row2, row3);
            for (var e : ing.entrySet()) recipe.setIngredient(e.getKey(), e.getValue());
            // Avoid duplicate
            try { getServer().removeRecipe(key); } catch (Exception ignored) {}
            getServer().addRecipe(recipe);
        } catch (Exception e) {
            getLogger().warning("Failed recipe for " + typeId + ": " + e.getMessage());
        }
    }
}
