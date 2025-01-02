package com.chadgamer938.syncplugin;

import com.mongodb.client.*;
import com.mongodb.client.model.UpdateOptions;
import net.milkbowl.vault.economy.Economy;
import org.bson.Document;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SyncPlugin extends JavaPlugin implements Listener {

    private MongoClient mongoClient;
    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private Economy economy;
    private final Map<String, Double> balanceCache = new ConcurrentHashMap<>();
    private final Map<String, String> syncCodes = new ConcurrentHashMap<>();
    private FileConfiguration config;

    @Override
    public void onEnable() {
        getLogger().info("SyncPlugin has been enabled!");

        saveDefaultConfig();
        config = getConfig();
        String mongoUri = config.getString("mongoUri");

        try {
            mongoClient = MongoClients.create(mongoUri);
            database = mongoClient.getDatabase("Players");
            collection = database.getCollection("SyncedPlayers");
            getLogger().info("Successfully connected to the database.");
        } catch (Exception e) {
            getLogger().severe("Failed to connect to MongoDB: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }

        if (!setupEconomy()) {
            getLogger().severe("Vault economy plugin not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("sync").setExecutor(this);

        getServer().getScheduler().runTaskTimer(this, this::syncAllPlayers, 1200L, 1200L); // Sync every minute
    }

    @Override
    public void onDisable() {
        for (Map.Entry<String, Double> entry : balanceCache.entrySet()) {
            saveBalanceToDatabase(entry.getKey(), entry.getValue());
        }
        if (mongoClient != null) {
            mongoClient.close();
            getLogger().info("MongoDB connection closed.");
        }
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        if (!hasSynced(username)) {
            player.sendMessage(ChatColor.RED + "Please sync your account using /sync before you can use your balance.");
            return;
        }

        syncBalanceFromDatabase(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();
        double currentBalance = economy.getBalance(player);
        saveBalanceToDatabase(username, currentBalance);
    }

    private void syncBalanceFromDatabase(Player player) {
        String username = player.getName();
        Document result = fetchBalanceDocument(username);

        if (result != null) {
            double databaseBalance = result.getDouble("balance");
            double currentBalance = economy.getBalance(player);

            if (databaseBalance != currentBalance) {
                economy.withdrawPlayer(player, currentBalance);
                economy.depositPlayer(player, databaseBalance);
                balanceCache.put(username, databaseBalance);
                getLogger().info("Synced balance for " + username + " to " + databaseBalance);
            }
        } else {
            double currentBalance = economy.getBalance(player);
            saveBalanceToDatabase(username, currentBalance);
        }
    }

    public void updatePlayerBalance(String username, double balance) {
        balanceCache.put(username, balance);
        saveBalanceToDatabase(username, balance);
    }

    private void saveBalanceToDatabase(String username, double balance) {
        try {
            Document document = new Document("username", username)
                    .append("balance", balance)
                    .append("lastUpdated", System.currentTimeMillis());

            collection.updateOne(
                    new Document("username", username),
                    new Document("$set", document),
                    new UpdateOptions().upsert(true)
            );
            getLogger().info("Balance saved for " + username);
        } catch (Exception e) {
            getLogger().severe("Error saving balance for " + username + ": " + e.getMessage());
        }
    }

    private Document fetchBalanceDocument(String username) {
        return collection.find(new Document("username", username)).first();
    }

    private boolean hasSynced(String username) {
        Document result = fetchBalanceDocument(username);
        return result != null && result.getBoolean("synced", false);
    }

    private void syncAllPlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            syncBalanceFromDatabase(player);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sync")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                String username = player.getName();
                Document result = fetchBalanceDocument(username);

                String syncCode;
                if (result != null && result.containsKey("syncCode")) {
                    syncCode = result.getString("syncCode");
                    player.sendMessage(ChatColor.GREEN + "Your account is already synced.");
                } else {
                    syncCode = generateUniqueCode();
                    syncCodes.put(username, syncCode);
                    player.sendMessage(ChatColor.GREEN + "Your sync code is: " + syncCode);
                }

                double currentBalance = economy.getBalance(player);
                saveBalanceToDatabase(username, currentBalance);
                return true;
            } else {
                sender.sendMessage("This command can only be used by a player.");
                return false;
            }
        }
        return false;
    }

    private String generateUniqueCode() {
        SecureRandom random = new SecureRandom();
        return String.valueOf(random.nextInt(900000000) + 100000000);
    }
}
