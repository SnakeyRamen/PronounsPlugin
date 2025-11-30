package com.quietterminal.pronounsplugin;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class PronounsPlugin extends JavaPlugin implements Listener {
    private Map<UUID, String> pronounsData;
    private File pronounsFile;
    private final Gson gson = new Gson();
    private volatile boolean saveScheduled = false;
    private static final Pattern HEX_PATTERN = Pattern.compile("^#[a-fA-F0-9]{6}$");
    private static final List<String> AVAILABLE_COLORS = NamedTextColor.NAMES.keys().stream().toList();
    private BukkitAudiences adventure;
    private boolean isPaperServer = false;
    private boolean isFolia = false;
    private Method paperDisplayNameMethod = null;
    private Method paperPlayerListNameMethod = null;

    @Override
    public void onEnable() {
        getLogger().info("Enabling PronounsPlugin...");
        detectServerType();
        saveDefaultConfig();
        pronounsFile = new File(getDataFolder(), "pronouns.json");
        pronounsData = new ConcurrentHashMap<>();
        loadPronounsDataAsync().thenRun(() -> {
            getLogger().info("Pronouns data loaded successfully!");
        }).exceptionally(throwable -> {
            getLogger().log(Level.SEVERE, "Failed to load pronouns data: {0}", throwable.getMessage());
            return null;
        });
        var pronounsCommand = getCommand("pronouns");
        if (pronounsCommand == null) {
            getLogger().severe("Failed to register '/pronouns' command. Check plugin.yml.");
        } else {
            pronounsCommand.setExecutor(this);
            pronounsCommand.setTabCompleter(new PronounsTabCompleter());
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PronounsExpansion(this).register();
            getLogger().info("PlaceholderAPI found. Registered %pronouns_pronouns%");
        } else {
            getLogger().warning("PlaceholderAPI not found! Pronoun placeholders will not work.");
        }
        setupBStatsCharts();
        this.adventure = BukkitAudiences.create(this);
        getLogger().info("Running on " + (isFolia ? "Folia" : (isPaperServer ? "Paper" : "Spigot")) + " - compatibility mode enabled");
    }

    private void detectServerType() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
            isPaperServer = true;
            getLogger().info("Folia server detected - using region-safe schedulers");
        } catch (ClassNotFoundException ignored) {
            try {
                paperDisplayNameMethod = Player.class.getMethod("displayName", Component.class);
                paperPlayerListNameMethod = Player.class.getMethod("playerListName", Component.class);
                isPaperServer = true;
                getLogger().info("Paper server detected - using enhanced display name features");
            } catch (NoSuchMethodException e) {
                isPaperServer = false;
                getLogger().info("Spigot server detected - using legacy name compatibility");
            }
        }
    }

    @Override
    public void onDisable() {
        savePronounsDataSync();
        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        scheduleSave();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isFolia) {
            Bukkit.getRegionScheduler().run(this, player.getLocation(), task -> updatePlayerName(player));
        } else {
            Bukkit.getScheduler().runTask(this, () -> updatePlayerName(player));
        }
    }

    public Map<UUID, String> getPronounsData() {
        return pronounsData;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sendMsg(sender, "<red>Usage: /pronouns <set|remove|reload>");
            return false;
        }
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "set" -> {
                return handleSetCommand(sender, args);
            }
            case "remove" -> {
                return handleRemoveCommand(sender, args);
            }
            case "reload" -> {
                return handleReloadCommand(sender);
            }
            case "add" -> {
                sendMsg(sender, "<yellow>The 'add' command is deprecated. Use '/pronouns set <color> <pronouns>' instead.");
                return handleSetCommand(sender, args);
            }
            case "gradient" -> {
                sendMsg(sender, "<yellow>The 'gradient' command is deprecated. Use '/pronouns set gradient <preset|colors> <pronouns>' instead.");
                return false;
            }
            default -> sendMsg(sender, "<red>Unknown subcommand. Use /pronouns <set|remove|reload>.");
        }
        return true;
    }

    private boolean handleSetCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            String message = getConfig().getString("messages.player-only", "&cOnly players can use this command.");
            sendMsg(sender, message);
            return false;
        }
        if (args.length < 3) {
            sendMsg(sender, "<red>Usage: /pronouns set <color|gradient> <color_name|preset> <pronouns>");
            sendMsg(sender, "<yellow>Examples:");
            sendMsg(sender, "<yellow>  /pronouns set white they/them");
            sendMsg(sender, "<yellow>  /pronouns set gradient trans they/them");
            sendMsg(sender, "<yellow>  /pronouns set gradient #ff0000 #0000ff she/her");
            return false;
        }
        String firstArg = args[1].toLowerCase();
        if (firstArg.equals("gradient")) {
            return handleGradientSet(player, args);
        } else {
            return handleSimpleColorSet(player, args);
        }
    }

    private boolean handleRemoveCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) {
                if (pronounsData.containsKey(player.getUniqueId())) {
                    pronounsData.remove(player.getUniqueId());
                    String message = getConfig().getString("messages.pronouns-removed", "&aYour pronouns have been removed.");
                    sendMsg(sender, message);
                    updatePlayerName(player);
                    scheduleSave();
                } else {
                    sendMsg(sender, "<red>You have no pronouns set.");
                }
            } else {
                sendMsg(sender, "<red>Usage: /pronouns remove <player>");
            }
            return false;
        }
        if (sender.isOp()) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target != null) {
                if (pronounsData.containsKey(target.getUniqueId())) {
                    pronounsData.remove(target.getUniqueId());
                    sendMsg(sender, "<green>Removed pronouns for " + target.getName());
                    updatePlayerName(target);
                    scheduleSave();
                } else {
                    sendMsg(sender, "<red>" + target.getName() + " has no pronouns set.");
                }
            } else {
                sendMsg(sender, "<red>Player not found.");
            }
        } else {
            String message = getConfig().getString("messages.no-permission", "&cYou do not have permission to use this command.");
            sendMsg(sender, message);
        }
        return true;
    }

    private boolean handleSimpleColorSet(Player player, String[] args) {
        String colorName = args[1].toUpperCase();
        NamedTextColor color = NamedTextColor.NAMES.value(colorName.toLowerCase());
        if (color == null) {
            color = NamedTextColor.GRAY;
        }
        String pronouns = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        int maxLength = getConfig().getInt("general.max-pronoun-length", 20);
        if (pronouns.length() > maxLength) {
            String message = getConfig().getString("messages.max-length-exceeded", "&cPronouns too long! Maximum length: {limit} characters.")
                    .replace("{limit}", String.valueOf(maxLength));
            sendMsg(player, message);
            return false;
        }
        pronounsData.put(player.getUniqueId(), color.toString() + ":" + pronouns);
        String baseMessage = getConfig().getString("messages.pronouns-set", "&aYour pronouns have been set to: {pronouns}");
        String formattedMessage = baseMessage.replace("{pronouns}", "[" + pronouns + "]");
        formattedMessage = convertLegacyToMiniMessage(formattedMessage);
        sendMsg(player, formattedMessage);
        updatePlayerName(player);
        scheduleSave();
        return true;
    }

    private boolean handleGradientSet(Player player, String[] args) {
        if (args.length < 4) {
            sendMsg(player, "<red>Usage: /pronouns set gradient <preset|color1> [color2] [color3] ... <pronouns>");
            sendMsg(player, "<yellow>Available presets: " + String.join(", ", getGradientPresets().keySet()));
            sendMsg(player, "<yellow>Examples:");
            sendMsg(player, "<yellow>  /pronouns set gradient trans they/them");
            sendMsg(player, "<yellow>  /pronouns set gradient #ff0000 #0000ff she/her");
            sendMsg(player, "<yellow>  /pronouns set gradient red blue green he/him");
            return false;
        }
        List<Color> colors = new ArrayList<>();
        String pronouns;
        String secondArg = args[2].toLowerCase();
        Map<String, String> presets = getGradientPresets();
        if (presets.containsKey(secondArg)) {
            String presetColors = presets.get(secondArg);
            String[] presetColorArray = presetColors.split("\\s+");
            for (String colorStr : presetColorArray) {
                Color color = parseColor(colorStr.trim());
                if (color == null) {
                    sendMsg(player, "<red>Invalid color in preset '" + secondArg + "': " + colorStr);
                    return false;
                }
                colors.add(color);
            }
            pronouns = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            sendMsg(player, "<green>Using preset: <gold>" + secondArg);
        } else {
            int pronounStartIndex = -1;
            for (int i = 2; i < args.length; i++) {
                Color color = parseColor(args[i]);
                if (color == null) {
                    pronounStartIndex = i;
                    break;
                }
                colors.add(color);
            }
            if (pronounStartIndex == -1) {
                sendMsg(player, "<red>No pronouns specified! Format: /pronouns set gradient <colors...> <pronouns>");
                return false;
            }
            if (colors.size() < 2) {
                sendMsg(player, "<red>Gradient requires at least 2 colors!");
                return false;
            }
            pronouns = String.join(" ", Arrays.copyOfRange(args, pronounStartIndex, args.length));
            int colorLimit = getConfig().getInt("gradient.color-limit", 5);
            if (colorLimit > 0 && colors.size() > colorLimit && !player.hasPermission("pronouns.gradient-limit-bypass")) {
                String message = getConfig().getString("messages.gradient-limit-exceeded", "&cToo many colors! Maximum allowed: {limit}.")
                        .replace("{limit}", String.valueOf(colorLimit));
                sendMsg(player, message);
                return false;
            }
        }
        int maxLength = getConfig().getInt("general.max-pronoun-length", 20);
        if (pronouns.length() > maxLength) {
            String message = getConfig().getString("messages.max-length-exceeded", "&cPronouns too long! Maximum length: {limit} characters.")
                    .replace("{limit}", String.valueOf(maxLength));
            sendMsg(player, message);
            return false;
        }
        String gradientText = applyGradient(pronouns, colors);
        pronounsData.put(player.getUniqueId(), "GRADIENT:" + gradientText);
        String message = getConfig().getString("messages.gradient-applied", "&aApplied gradient to your pronouns:");
        sendMsg(player, message);
        if (getConfig().getBoolean("gradient.show-preview", true)) {
            sendMsg(player, gradientText);
        }
        updatePlayerName(player);
        scheduleSave();
        return true;
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("pronouns.reload")) {
            reloadConfig();
            sendMsg(sender, "<green>PronounsPlugin configuration reloaded.");
        } else {
            sendMsg(sender, getConfig().getString("messages.no-permission", "&cYou do not have permission to use this command."));
        }
        return true;
    }

    private String convertLegacyToMiniMessage(String message) {
        return message
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>")
                .replace("&k", "<obf>")
                .replace("&l", "<b>")
                .replace("&m", "<st>")
                .replace("&n", "<u>")
                .replace("&o", "<i>")
                .replace("&r", "<reset>");
    }

    @SuppressWarnings("null")
    private void sendMsg(CommandSender sender, String msg) {
        if (sender instanceof Player player) {
            if (isPaperServer) {
                if (this.adventure != null) {
                    if (msg.contains("&") || msg.contains("§")) {
                        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
                        this.adventure.player(player).sendMessage(component);
                    } else {
                        Component component = MiniMessage.miniMessage().deserialize(msg);
                        this.adventure.player(player).sendMessage(component);
                    }
                } else {
                    String legacyMsg = convertToLegacyString(msg);
                    player.sendMessage(legacyMsg);
                }
            } else {
                String legacyMsg = convertToLegacyString(msg);
                player.sendMessage(legacyMsg);
            }
        } else {
            String legacyMsg = convertToLegacyString(msg);
            sender.sendMessage(legacyMsg);
        }
    }

    private String convertToLegacyString(String msg) {
        if (msg.contains("&") || msg.contains("§")) {
            return msg.replace('&', '§');
        } else {
            try {
                Component component = MiniMessage.miniMessage().deserialize(msg);
                return LegacyComponentSerializer.legacySection().serialize(component);
            } catch (Exception e) {
                return msg;
            }
        }
    }

    private Color parseColor(String colorString) {
        if (HEX_PATTERN.matcher(colorString).matches()) {
            try {
                return Color.decode(colorString);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        NamedTextColor legacyColor = NamedTextColor.NAMES.value(colorString.toLowerCase());
        if (legacyColor != null) {
            return new Color(legacyColor.value());
        }
        return null;
    }

    private String applyGradient(String text, List<Color> colors) {
        if (text.isEmpty() || colors.isEmpty()) {
            return text;
        }
        if (colors.size() == 1) {
            TextColor tc = TextColor.color(colors.get(0).getRGB());
            return "§x" + String.format("%06x", tc.value()).replaceAll("(.)", "§$1") + text + "§r";
        }
        StringBuilder result = new StringBuilder();
        int textLength = text.length();
        for (int i = 0; i < textLength; i++) {
            char c = text.charAt(i);
            float position = (float) i / (textLength - 1);
            float segmentSize = 1.0f / (colors.size() - 1);
            int segmentIndex = Math.min((int) (position / segmentSize), colors.size() - 2);
            float segmentPosition = (position - (segmentIndex * segmentSize)) / segmentSize;
            Color color1 = colors.get(segmentIndex);
            Color color2 = colors.get(segmentIndex + 1);
            Color interpolated = interpolateColor(color1, color2, segmentPosition);
            String hexColor = String.format("%06x", interpolated.getRGB() & 0xFFFFFF);
            result.append("§x")
                  .append("§").append(hexColor.charAt(0))
                  .append("§").append(hexColor.charAt(1))
                  .append("§").append(hexColor.charAt(2))
                  .append("§").append(hexColor.charAt(3))
                  .append("§").append(hexColor.charAt(4))
                  .append("§").append(hexColor.charAt(5))
                  .append(c);
        }
        result.append("§r");
        return result.toString();
    }

    private Color interpolateColor(Color c1, Color c2, float t) {
        int r = (int) (c1.getRed() + t * (c2.getRed() - c1.getRed()));
        int g = (int) (c1.getGreen() + t * (c2.getGreen() - c1.getGreen()));
        int b = (int) (c1.getBlue() + t * (c2.getBlue() - c1.getBlue()));
        return new Color(r, g, b);
    }

    private String stripAllColors(String text) {
        text = text.replaceAll("§x(§[0-9a-f]){6}", "");
        text = text.replaceAll("§[0-9a-fk-or]", "");
        return text;
    }

    private Map<String, String> getGradientPresets() {
        Map<String, String> presets = new HashMap<>();
        if (getConfig().isConfigurationSection("gradient.presets")) {
            var section = getConfig().getConfigurationSection("gradient.presets");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String colors = getConfig().getString("gradient.presets." + key);
                    if (colors != null && !colors.isEmpty()) {
                        presets.put(key.toLowerCase(), colors);
                    }
                }
            }
        }
        return presets;
    }

    private void scheduleSave() {
        if (!saveScheduled && getConfig().getBoolean("general.auto-save", true)) {
            saveScheduled = true;
            long delayTicks = getConfig().getLong("general.save-delay", 1) * 20L;
            if (isFolia) {
                Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
                    savePronounsDataAsync();
                    saveScheduled = false;
                }, delayTicks);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                    savePronounsDataAsync();
                    saveScheduled = false;
                }, delayTicks);
            }
        }
    }

    private CompletableFuture<Void> loadPronounsDataAsync() {
        return CompletableFuture.runAsync(() -> {
            if (!pronounsFile.exists()) {
                try {
                    getDataFolder().mkdirs();
                    pronounsFile.createNewFile();
                    Files.write(pronounsFile.toPath(), "{}".getBytes());
                } catch (IOException e) {
                    getLogger().severe("Could not create pronouns.json file!");
                    getLogger().log(Level.SEVERE, "Exception:", e);
                    return;
                }
            }
            try {
                String content = new String(Files.readAllBytes(pronounsFile.toPath()));
                Type type = new TypeToken<Map<UUID, String>>(){}.getType();
                Map<UUID, String> loadedData = gson.fromJson(content, type);
                if (loadedData != null) {
                    pronounsData.putAll(loadedData);
                }
            } catch (IOException e) {
                getLogger().severe("Could not read pronouns.json file!");
                getLogger().log(Level.SEVERE, "Exception:", e);
            }
        });
    }

    private void savePronounsDataAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                String json = gson.toJson(pronounsData);
                Files.write(pronounsFile.toPath(), json.getBytes());
            } catch (IOException e) {
                getLogger().severe("Could not save pronouns.json file!");
                getLogger().log(Level.SEVERE, "Exception:", e);
            }
        });
    }

    private void savePronounsDataSync() {
        try {
            String json = gson.toJson(pronounsData);
            Files.write(pronounsFile.toPath(), json.getBytes());
        } catch (IOException e) {
            getLogger().severe("Could not save pronouns.json file!");
            getLogger().log(Level.SEVERE, "Exception:", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void updatePlayerName(Player player) {
        if (!getConfig().getBoolean("general.update-display-names", true)) {
            return;
        }
        String storedData = pronounsData.get(player.getUniqueId());
        String playerName = player.getName();
        if (storedData != null) {
            Component formattedName;
            Component tabListName;
            if (storedData.startsWith("GRADIENT:")) {
                String gradientText = storedData.substring(9);
                Component gradientComponent = LegacyComponentSerializer.legacySection().deserialize(gradientText);
                formattedName = Component.text("[").append(gradientComponent).append(Component.text("] ")).append(Component.text(playerName));
                tabListName = Component.text(playerName).append(Component.text(" [")).append(Component.text(stripAllColors(gradientText))).append(Component.text("]"));
            } else {
                String[] parts = storedData.split(":", 2);
                NamedTextColor color = NamedTextColor.NAMES.value(parts[0].toLowerCase());
                if (color == null) color = NamedTextColor.GRAY;
                String pronouns = parts[1];
                formattedName = Component.text("[", color).append(Component.text(pronouns, color)).append(Component.text("] ")).append(Component.text(playerName));
                tabListName = Component.text(playerName).append(Component.text(" [" + pronouns + "]"));
            }
            if (isPaperServer && paperDisplayNameMethod != null && paperPlayerListNameMethod != null) {
                try {
                    paperDisplayNameMethod.invoke(player, formattedName);
                    player.setCustomName(LegacyComponentSerializer.legacySection().serialize(formattedName));
                    player.setCustomNameVisible(getConfig().getBoolean("chat.show-above-head", true));
                    if (getConfig().getBoolean("chat.show-in-tab-list", true)) {
                        paperPlayerListNameMethod.invoke(player, tabListName);
                    }
                } catch (Exception e) {
                    setSpigotDisplayName(player, formattedName, tabListName);
                }
            } else {
                setSpigotDisplayName(player, formattedName, tabListName);
            }
        } else {
            if (isPaperServer && paperDisplayNameMethod != null) {
                try {
                    Component nameComponent = Component.text(playerName);
                    paperDisplayNameMethod.invoke(player, nameComponent);
                    player.setCustomName(null);
                    player.setCustomNameVisible(false);
                    if (paperPlayerListNameMethod != null) {
                        paperPlayerListNameMethod.invoke(player, nameComponent);
                    }
                } catch (Exception e) {
                    player.setCustomName(null);
                    player.setCustomNameVisible(false);
                    player.setPlayerListName(null);
                }
            } else {
                player.setDisplayName(playerName);
                player.setCustomName(null);
                player.setCustomNameVisible(false);
                player.setPlayerListName(playerName);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void setSpigotDisplayName(Player player, Component formattedName, Component tabListName) {
        String legacyFormattedName = LegacyComponentSerializer.legacySection().serialize(formattedName);
        String legacyTabName = LegacyComponentSerializer.legacySection().serialize(tabListName);
        player.setDisplayName(legacyFormattedName);
        player.setCustomName(legacyFormattedName);
        player.setCustomNameVisible(getConfig().getBoolean("chat.show-above-head", true));
        if (getConfig().getBoolean("chat.show-in-tab-list", true)) {
            player.setPlayerListName(legacyTabName);
        }
    }

    private class PronounsTabCompleter implements TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return Arrays.asList("set", "remove", "reload").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                List<String> suggestions = new ArrayList<>(AVAILABLE_COLORS);
                suggestions.add("gradient");
                return suggestions.stream()
                        .filter(color -> color.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("gradient")) {
                List<String> suggestions = new ArrayList<>();
                Map<String, String> presets = getGradientPresets();
                suggestions.addAll(presets.keySet());
                suggestions.addAll(AVAILABLE_COLORS);
                suggestions.addAll(Arrays.asList("#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff", "#00ffff", "#ffffff", "#000000"));
                return suggestions.stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if (args.length >= 4 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("gradient")) {
                Map<String, String> presets = getGradientPresets();
                if (!presets.containsKey(args[2].toLowerCase())) {
                    Color lastColor = parseColor(args[args.length - 1]);
                    if (lastColor != null) {
                        List<String> suggestions = new ArrayList<>(AVAILABLE_COLORS);
                        suggestions.addAll(Arrays.asList("#ff0000", "#00ff00", "#0000ff", "#ffff00", "#ff00ff", "#00ffff"));
                        return suggestions;
                    }
                }
                return Collections.emptyList();
            }
            return Collections.emptyList();
        }
    }
}
