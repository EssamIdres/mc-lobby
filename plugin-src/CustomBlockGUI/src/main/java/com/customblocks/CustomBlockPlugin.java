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

    // Track custom block locations as "world:x:y:z"
    private final Set<String> customBlocks = new HashSet<>();
    // Per-block storage: locationKey -> ItemStack[27]
    private final Map<String, ItemStack[]> blockStorages = new HashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;

    // The block that visually represents custom block
    public static final Material CUSTOM_BLOCK_MATERIAL = Material.SMITHING_TABLE;
    // Alternative: Material.BARREL, Material.DROPPER, etc.

    @Override
    public void onEnable() {
        instance = this;
        customBlockKey = new NamespacedKey(this, "custom_block");

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

        getLogger().info("CustomBlockGUI v1.0.0 enabled! Custom block: " + CUSTOM_BLOCK_MATERIAL);
        getLogger().info("Use /givecustomblock to get the block | Place it & right-click to open GUI");
    }

    @Override
    public void onDisable() {
        if (dataConfig != null && dataFile != null) {
            saveCustomBlocks();
            getLogger().info("CustomBlockGUI disabled, saved " + customBlocks.size() + " blocks");
        }
    }

    public static CustomBlockPlugin getInstance() {
        return instance;
    }

    // === ITEM CREATION ===
    public ItemStack createCustomBlockItem(int amount) {
        ItemStack item = new ItemStack(CUSTOM_BLOCK_MATERIAL, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text("§6§lCustom Machine §7(Right-click to open)"));
            meta.lore(List.of(
                    net.kyori.adventure.text.Component.text("§7A custom block with"),
                    net.kyori.adventure.text.Component.text("§7a custom GUI!"),
                    net.kyori.adventure.text.Component.text(""),
                    net.kyori.adventure.text.Component.text("§e► Place it on the ground"),
                    net.kyori.adventure.text.Component.text("§e► Right-click to open menu"),
                    net.kyori.adventure.text.Component.text("§e► Shift-right-click to rotate"),
                    net.kyori.adventure.text.Component.text(""),
                    net.kyori.adventure.text.Component.text("§8CustomBlock §7| §f1.21.1")
            ));
            meta.getPersistentDataContainer().set(customBlockKey, PersistentDataType.BYTE, (byte) 1);
            // Add enchant glint for effect (optional)
            meta.setEnchantmentGlintOverride(true);
            // CustomModelData for resource pack support - you can assign your own model
            meta.setCustomModelData(1001);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isCustomBlockItem(ItemStack item) {
        if (item == null || item.getType() != CUSTOM_BLOCK_MATERIAL) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(customBlockKey, PersistentDataType.BYTE);
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
        return customBlocks.contains(locToKey(loc));
    }

    public void addCustomBlock(Location loc) {
        customBlocks.add(locToKey(loc));
        saveCustomBlocks();
    }

    public void removeCustomBlock(Location loc) {
        String key = locToKey(loc);
        customBlocks.remove(key);
        // Drop storage contents if any
        blockStorages.remove(key);
        saveCustomBlocks();
    }

    public Set<String> getCustomBlocks() {
        return customBlocks;
    }

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
        customBlocks.clear();
        blockStorages.clear();
        List<String> list = dataConfig.getStringList("blocks");
        customBlocks.addAll(list);
        getLogger().info("Loaded " + customBlocks.size() + " custom blocks from file");

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
        customBlocks.removeIf(key -> keyToLoc(key) == null);
    }

    public void saveCustomBlocks() {
        if (dataConfig == null || dataFile == null) return;
        try {
            dataConfig.set("blocks", new ArrayList<>(customBlocks));
            // Save storages
            dataConfig.set("storages", null);
            for (Map.Entry<String, ItemStack[]> entry : blockStorages.entrySet()) {
                // Only save non-empty storages
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
