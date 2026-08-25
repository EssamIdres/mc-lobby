package com.customblocks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

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

        getLogger().info("CustomBlockGUI v1.1.0 enabled! " + BLOCK_TYPES.size() + " custom blocks: " + String.join(", ", BLOCK_TYPES.keySet()));
        getLogger().info("Use /givecustomblock <type> or /givecustomblock help | Textures via DiscoveryLab-pack (new way, no Oraxen)");
    }

    @Override
    public void onDisable() {
        if (dataConfig != null && dataFile != null) {
            saveCustomBlocks();
            getLogger().info("CustomBlockGUI disabled, saved " + blockTypes.size() + " blocks");
        }
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
            meta.setEnchantmentGlintOverride(true);
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

    public void addCustomBlock(Location loc, String typeId) {
        String key = locToKey(loc);
        String type = getBlockTypeDef(typeId).id;
        blockTypes.put(key, type);
        customBlocks.add(key);
        saveCustomBlocks();
    }

    public void addCustomBlock(Location loc) {
        addCustomBlock(loc, "machine");
    }

    public void removeCustomBlock(Location loc) {
        String key = locToKey(loc);
        blockTypes.remove(key);
        customBlocks.remove(key);
        blockStorages.remove(key);
        saveCustomBlocks();
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

        // Validate (remove invalid worlds)
        blockTypes.keySet().removeIf(key -> keyToLoc(key) == null);
        customBlocks.removeIf(key -> keyToLoc(key) == null);
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
}
