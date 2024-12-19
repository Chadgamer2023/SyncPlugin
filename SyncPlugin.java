/*
 * MIT License
 * 
 * Copyright (c) [2024] [chadgamer938]
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.chadgamer938.syncplugin;

import com.mongodb.client.*;
import com.mongodb.client.model.UpdateOptions;
import net.milkbowl.vault.economy.Economy;
import org.bson.Document;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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

        Logger mongoLogger = Logger.getLogger("org.mongodb.driver");
        mongoLogger.setLevel(Level.SEVERE);

        String mongoUri = config.getString("mongodb.uri");

        if (mongoUri == null || mongoUri.equals("mongodb://your_mongodb_uri_here")) {
            getLogger().severe("MongoDB URI is not set in the configuration file. Please update config.yml with your MongoDB URI.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            mongoClient = MongoClients.create(mongoUri);
            database = mongoClient.getDatabase("PlayersSynced");
            collection = database.getCollection("SyncedPlayers");
            getLogger().info("Successfully connected to the database.");
        } catch (Exception e) {
            getLogger().severe("Failed to connect to MongoDB: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }

        // Hook into Vault
        if (!setupEconomy()) {
            getLogger().severe("Vault economy plugin not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register events and commands
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("sync").setExecutor(this);
    }

    @Override
    public void onDisable() {
        getLogger().info("SyncPlugin has been disabled!");

        for (Map.Entry<String, Double> entry : balanceCache.entrySet()) {
            saveBalanceToDatabase(entry.getKey(), entry.getValue());
        }

        if (mongoClient != null) {
            mongoClient.close();
            getLogger().info("MongoDB connection closed.");
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
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
            String lastUpdated = result.getString("lastUpdated");
            String now = java.time.Instant.now().toString();

            if (lastUpdated.compareTo(now) < 0) {
                saveBalanceToDatabase(username, currentBalance);
            } else {
                economy.withdrawPlayer(player, currentBalance);
                economy.depositPlayer(player, databaseBalance);
                balanceCache.put(username, databaseBalance);
                getLogger().info("Updated in-game balance for " + username + ": " + databaseBalance);
            }
        } else {
            double currentBalance = economy.getBalance(player);
            saveBalanceToDatabase(username, currentBalance);
        }
    }

    public void saveBalanceToDatabase(String username, double balance) {
        int attempts = 0;
        boolean success = false;
        String now = java.time.Instant.now().toString();

        while (attempts < 3 && !success) {
            try {
                Document document = new Document("username", username)
                        .append("balance", balance)
                        .append("lastUpdated", now);
                collection.updateOne(new Document("username", username), new Document("$set", document), new UpdateOptions().upsert(true));
                success = true;
            } catch (Exception e) {
                attempts++;
                getLogger().severe("Failed to save balance to MongoDB: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sync")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                String username = player.getName();
                Document result = fetchBalanceDocument(username);

                if (result != null && result.containsKey("syncCode")) {
                    player.sendMessage(ChatColor.GREEN + "Your account is already synced. Balance synced successfully.");
                } else {
                    String syncCode = generateUniqueCode();
                    syncCodes.put(username, syncCode);
                    player.sendMessage(ChatColor.GREEN + "Your sync code is: " + syncCode);
                    getLogger().info("Generated sync code for " + username + ": " + syncCode);
                }
                return true;
            } else {
                sender.sendMessage("This command can only be run by a player.");
                return false;
            }
        }
        return false;
    }

    private String generateUniqueCode() {
        SecureRandom random = new SecureRandom();
        int code = random.nextInt(900000000) + 100000000; // Generate a 9-digit code
        return String.valueOf(code);
    }

    private Document fetchBalanceDocument(String username) {
        return collection.find(new Document("username", username)).first();
    }
}
