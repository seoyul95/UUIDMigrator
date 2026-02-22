package me.server.fulldatabackup;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static java.net.http.HttpResponse.BodyHandlers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FullDataRestore extends JavaPlugin implements Listener, org.bukkit.command.CommandExecutor {
    // track UUIDs we've already processed during this server session to avoid
    // repeatedly kicking the same user when they rejoin after a restore
    private final Set<UUID> restored = Collections.synchronizedSet(new HashSet<>());

    // skin properties we know for cracked players. once we look up a skin
    // we keep it in the map so it can be reapplied on every subsequent login.
    // this avoids losing the texture when the player disconnects (Bukkit
    // doesn't persist the skin property) and also means a single relog after
    // restoration is sufficient.
    private final Map<UUID, ProfileProperty> skins = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("restoreall").setExecutor(this);
        getLogger().info("Full Data Restore Plugin Enabled");
        // automatically attempt to restore any preexisting cracked data
        restoreAll(null);
        // note: restoreAll logs "done" when finished
    }

    @Override
    public void onDisable() {
        // save players/worlds before shutdown so no in-memory changes are lost
        try {
            Bukkit.getServer().savePlayers();
            Bukkit.getWorlds().forEach(w -> w.save());
        } catch (Exception ignored) {
            // just attempt; failures logged by Bukkit itself
        }

        // if the server is shutting down while someone has just had their
        // files copied but hasn't yet been kicked, make sure we disconnect them
        // now so the restored data isn't overwritten by their old in-memory state
        for (UUID u : restored) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) {
                p.kickPlayer("§aServer is shutting down; please rejoin to load restored data");
            }
        }
        getLogger().info("Full Data Restore Plugin Disabled");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // if we know a skin for this cracked uuid, (re)apply it. we don't
        // remove it so it stays cached for future reconnects.
        ProfileProperty known = skins.get(player.getUniqueId());
        if (known != null) {
            getLogger().info("reapplying skin for " + player.getName());
            applySkin(player, known.getValue(), known.getSignature());
        }

        // run async in case we hit disk / network
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            try {
                String name = player.getName();

                // ⭐ 정품 UUID 조회 via Mojang API (offline server doesn't return real uuid)
                UUID onlineUUID = lookupRealUUID(name);
                if (onlineUUID == null) {
                    getLogger().info("could not resolve online UUID for " + name + "; skipping restore");
                    return;
                }
                getLogger().info("resolved " + name + " -> " + onlineUUID);
                UUID crackedUUID = player.getUniqueId();

                // already restored once? skip to avoid endless kick/rejoin loop
                if (restored.contains(crackedUUID)) {
                    getLogger().info(name + " already had data restored this session");
                    return;
                }

                // restore files (inventory, advancements, stats)
                boolean copied;
                try {
                    copied = restoreFiles(onlineUUID, crackedUUID);
                } catch (IOException ioe) {
                    getLogger().warning("could not copy data for " + name + ": " + ioe);
                    player.sendMessage("§cFailed to write restore files (permission error?)");
                    return;
                }

                if (copied) {
                    restored.add(crackedUUID);
                    // the data for the cracked UUID has already been loaded when the
                    // player joined. copying the files above won't change the in‑memory
                    // state, so force a relog so the server reads the online‑mode files.
                    Bukkit.getScheduler().runTask(this, () ->
                            player.kickPlayer("§aData restored – please rejoin to load your items"));

                    getLogger().info("Restoration scheduled for " + name + "; kicked for relog");
                } else {
                    getLogger().info("no online data available for " + name + "; nothing to restore");
                }

                // look up skin now; we cache and let join handler apply it automatically
                fetchSkin(crackedUUID, name);

            } catch (Exception ex) {
                getLogger().warning("onJoin error: " + ex.getMessage());
            }
        }, 60L);
    }

@EventHandler
public void onPreLogin(AsyncPlayerPreLoginEvent e) {
    String name = e.getName();
    UUID crackedUUID = e.getUniqueId();

    try {
        UUID onlineUUID = lookupRealUUID(name);
        if (onlineUUID == null) {
            getLogger().info("No premium UUID for " + name);
            return;
        }

        getLogger().info("Resolved " + name + " -> " + onlineUUID);

        boolean copied = restoreFiles(onlineUUID, crackedUUID);

        if (copied) {
            getLogger().info("Pre-login restore complete for " + name);
        }

        fetchSkin(crackedUUID, name);

    } catch (Exception ex) {
        getLogger().warning("PreLogin restore error: " + ex.getMessage());
    }
}

    // ---------------- helper methods ----------------

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if ("restoreall".equalsIgnoreCase(command.getName())) {
            restoreAll(sender);
            return true;
        }
        return false;
    }

    /**
     * Attempt to restore data and skin for an offline player UUID using the
     * stored name value from Bukkit's OfflinePlayer record.
     */
    private void restoreForPlayer(UUID crackedUUID) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(crackedUUID);
        String name = off.getName();
        if (name == null) {
            getLogger().info("offline player " + crackedUUID + " has no name; skipping");
            return;
        }
        try {
            UUID onlineUUID = lookupRealUUID(name);
            if (onlineUUID == null) {
                getLogger().info("could not resolve online UUID for " + name + "; skipping");
                return;
            }
            getLogger().info("resolved " + name + " -> " + onlineUUID + " for offline " + crackedUUID);
            boolean copied = restoreFiles(onlineUUID, crackedUUID);
            if (copied) {
                getLogger().info("restored data for " + name + " (" + crackedUUID + ")");

                // if the cracked player is currently online, kick them so their
                // in‑memory state (inventory/advancements/etc.) gets reloaded.
                Player online = Bukkit.getPlayer(crackedUUID);
                if (online != null && online.isOnline()) {
                    Bukkit.getScheduler().runTask(this, () ->
                            online.kickPlayer("§aData restored – please rejoin to load your items"));
                }
            } else {
                getLogger().info("no online data for " + name + "; nothing to restore");
            }
            fetchSkin(crackedUUID, name);
        } catch (Exception ex) {
            getLogger().warning("restoreForPlayer error for " + crackedUUID + ": " + ex);
        }
    }

    /**
     * Kick off the asynchronous iteration over the playerdata folder.
     */
    private void restoreAll(org.bukkit.command.CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Path worldFolder = Bukkit.getWorlds().get(0).getWorldFolder().toPath();
                Path playerDataFolder = worldFolder.resolve("playerdata");
                Files.createDirectories(playerDataFolder);
                Files.list(playerDataFolder).forEach(path -> {
                    if (path.toString().endsWith(".dat")) {
                        String uuidStr = path.getFileName().toString().replaceFirst("\\.dat$", "");
                        try {
                            UUID cracked = UUID.fromString(uuidStr);
                            restoreForPlayer(cracked);
                        } catch (IllegalArgumentException ignored) {
                            // skip non-uuid files
                        }
                    }
                });
                if (sender != null) {
                    sender.sendMessage("§aRestore process initiated for all offline player files.");
                }
                getLogger().info("restoreall complete: done");
            } catch (Exception e) {
                if (sender != null) {
                    sender.sendMessage("§cError during restoreall: " + e.getMessage());
                }
                getLogger().warning("restoreall error: " + e);
            }
        });
    }

    /**
     * Resolve a Minecraft account name to its online-mode UUID using Mojang API.
     * Returns null if the username does not correspond to a premium account.
     */
    private UUID lookupRealUUID(String username) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                .build();
        HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
        if (resp.statusCode() != 200 || resp.body().isEmpty()) {
            return null;
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        String id = obj.get("id").getAsString(); // 32 hex chars
        // insert dashes to convert to java.util.UUID format
        String dashed = id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }

    /**
     * Copy the three data files from onlineUUID to crackedUUID if they exist.
     *
     * @return true if at least one file was copied, false if none of the online
     *     files were present (nothing to restore).
     * @throws IOException propagated so caller can report permission errors, etc.
     */
    private boolean restoreFiles(UUID onlineUUID, UUID crackedUUID) throws IOException {
        Path worldFolder = Bukkit.getWorlds().get(0).getWorldFolder().toPath();
        getLogger().info("using world folder " + worldFolder);
        Path playerDataFolder = worldFolder.resolve("playerdata");
        Path advancementsFolder = worldFolder.resolve("advancements");
        Path statsFolder = worldFolder.resolve("stats");

        Files.createDirectories(playerDataFolder);
        Files.createDirectories(advancementsFolder);
        Files.createDirectories(statsFolder);

        boolean copiedAny = false;
        Path onlineData = playerDataFolder.resolve(onlineUUID + ".dat");
        Path crackedData = playerDataFolder.resolve(crackedUUID + ".dat");
        getLogger().info("online data file " + onlineData + " exists=" + Files.exists(onlineData));
        copiedAny |= copyIfExists(onlineData, crackedData);
        Path onlineAdv = advancementsFolder.resolve(onlineUUID + ".json");
        Path crackedAdv = advancementsFolder.resolve(crackedUUID + ".json");
        getLogger().info("online adv file " + onlineAdv + " exists=" + Files.exists(onlineAdv));
        copiedAny |= copyIfExists(onlineAdv, crackedAdv);
        Path onlineStats = statsFolder.resolve(onlineUUID + ".json");
        Path crackedStats = statsFolder.resolve(crackedUUID + ".json");
        getLogger().info("online stats file " + onlineStats + " exists=" + Files.exists(onlineStats));
        copiedAny |= copyIfExists(onlineStats, crackedStats);
        return copiedAny;
    }

    /**
     * Copy source->dest if source exists.
     *
     * @return true if copy was performed, false if source didn't exist.
     */
    private boolean copyIfExists(Path source, Path dest) throws IOException {
        if (Files.exists(source)) {
            Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("copied " + source.getFileName() + " to " + dest.getFileName());
            return true;
        }
        return false;
    }

    // old synchronous skin-restoration method removed; we now have a utility
    // that works with offline UUIDs and caches the result.

    private void fetchSkin(UUID crackedUuid, String username) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                getLogger().info("starting skin lookup for " + username);
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest uuidReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                        .build();
                HttpResponse<String> uuidResp = client.send(uuidReq, HttpResponse.BodyHandlers.ofString());
                if (uuidResp.statusCode() != 200 || uuidResp.body().isEmpty()) {
                    getLogger().info("no online account found for " + username + " (" + uuidResp.statusCode() + ")");
                    return;
                }
                JsonObject uuidObj = JsonParser.parseString(uuidResp.body()).getAsJsonObject();
                String id = uuidObj.get("id").getAsString(); // no dashes
                getLogger().info("resolved online UUID for skin: " + id);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    getLogger().info("no profile for " + username + " (" + resp.statusCode() + ")");
                    return;
                }
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray props = root.getAsJsonArray("properties");
                for (int i = 0; i < props.size(); i++) {
                    JsonObject prop = props.get(i).getAsJsonObject();
                    if ("textures".equals(prop.get("name").getAsString())) {
                        String value = prop.get("value").getAsString();
                        String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
                        ProfileProperty propObj = new ProfileProperty("textures", value, signature);
                        skins.put(crackedUuid, propObj);
                        getLogger().info("skin cached for " + username + " (" + crackedUuid + ")");
                        return;
                    }
                }
                getLogger().info("no textures property for " + username);
            } catch (IOException | InterruptedException ex) {
                getLogger().warning("skin lookup failed for " + username + ": " + ex.getMessage());
            }
        });
    }

    private void applySkin(Player player, String value, String signature) {
        // cast to Paper profile because Bukkit's interface is too limited
        PlayerProfile profile = (PlayerProfile) player.getPlayerProfile();
        ProfileProperty prop = new ProfileProperty("textures", value, signature);
        profile.getProperties().removeIf(p -> "textures".equals(p.getName()));
        profile.getProperties().add(prop);
        player.setPlayerProfile(profile);
        player.sendMessage("§eSkin property applied; rejoin to see changes.");
    }}