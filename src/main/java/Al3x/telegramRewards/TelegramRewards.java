package Al3x.telegramRewards;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TelegramRewards extends JavaPlugin implements Listener, CommandExecutor {

    private FileConfiguration playersConfig;
    private File playersFile;
    private Map<UUID, Boolean> rewardedPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createPlayersConfig();
        loadPlayerData();

        getCommand("socialreward").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Telegram Rewards plugin enabled!");
    }

    private void createPlayersConfig() {
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            playersFile.getParentFile().mkdirs();
            try {
                playersFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Не удалось создать players.yml!");
            }
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);
    }

    private void loadPlayerData() {
        rewardedPlayers.clear();
        if (playersConfig.contains("players")) {
            for (String key : playersConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    boolean rewarded = playersConfig.getBoolean("players." + key + ".rewarded");
                    rewardedPlayers.put(uuid, rewarded);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Неверный UUID в players.yml: " + key);
                }
            }
        }
    }

    private void savePlayerData() {
        try {
            playersConfig.save(playersFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить players.yml!");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!rewardedPlayers.containsKey(uuid) || !rewardedPlayers.get(uuid)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&6🎮 Получи награду за подписку на наш Telegram!"));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7Команда: &a/socialreward @ваш_username"));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков!");
            return true;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        FileConfiguration config = getConfig();

        if (args.length != 1) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    config.getString("messages.usage", "&eИспользование: &6/socialreward @username")));
            return true;
        }

        String telegramUsername = args[0];

        if (!telegramUsername.startsWith("@")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    config.getString("messages.no-username", "&c❌ Укажите username начиная с @")));
            return true;
        }

        if (rewardedPlayers.containsKey(uuid) && rewardedPlayers.get(uuid)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    config.getString("messages.already-received", "&c❌ Вы уже получали награду!")));
            return true;
        }

        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                config.getString("messages.checking", "&7Проверяем подписку...")));

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            CheckResult result = checkTelegramSubscription(telegramUsername.substring(1));

            Bukkit.getScheduler().runTask(this, () -> {
                switch (result.status) {
                    case SUCCESS:
                        if (result.isSubscribed) {
                            giveReward(player);
                            rewardedPlayers.put(uuid, true);
                            playersConfig.set("players." + uuid.toString() + ".rewarded", true);
                            playersConfig.set("players." + uuid.toString() + ".telegram", telegramUsername);
                            playersConfig.set("players." + uuid.toString() + ".name", player.getName());
                            savePlayerData();
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                    config.getString("messages.subscribed", "&a✅ Вы подписаны! Награда выдана!")));
                        } else {
                            String channel = config.getString("channel", "@yourchannel");
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                    config.getString("messages.not-subscribed", "&c❌ Вы не подписаны на канал {channel}")
                                            .replace("{channel}", channel)));
                        }
                        break;
                    case ERROR:
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                config.getString("messages.error", "&c⚠️ Ошибка проверки. Попробуйте позже.")));
                        break;
                    case API_ERROR:
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                config.getString("messages.api-error", "&c⚠️ Ошибка связи с Telegram. Сообщите администратору.")));
                        break;
                    case BOT_ERROR:
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                config.getString("messages.bot-error", "&c⚠️ Бот не имеет прав для проверки подписки. Сообщите администратору.")));
                        break;
                }
            });
        });

        return true;
    }

    private CheckResult checkTelegramSubscription(String telegramUsername) {
        try {
            String botToken = getConfig().getString("bot-token");
            String channel = getConfig().getString("channel").replace("@", "");

            // Шаг 1: Получаем user_id через getUpdates (если пользователь писал боту)
            Long userId = getUserIdFromUpdates(botToken, telegramUsername);

            if (userId == null) {
                getLogger().warning("Не удалось найти user_id для: @" + telegramUsername);
                return new CheckResult(CheckStatus.ERROR, false);
            }

            getLogger().info("Найден user_id: " + userId + " для: @" + telegramUsername);

            // Шаг 2: Проверяем подписку используя user_id
            String checkSubscriptionUrl = "https://api.telegram.org/bot" + botToken +
                    "/getChatMember?chat_id=@" + channel +
                    "&user_id=" + userId;

            String response = makeApiRequest(checkSubscriptionUrl);

            if (response == null) {
                return new CheckResult(CheckStatus.API_ERROR, false);
            }

            getLogger().info("Subscription check response: " + response);

            // Проверяем статус подписки
            if (response.contains("\"status\":\"member\"") ||
                    response.contains("\"status\":\"administrator\"") ||
                    response.contains("\"status\":\"creator\"") ||
                    response.contains("\"status\":\"restricted\"")) {
                return new CheckResult(CheckStatus.SUCCESS, true);
            }

            // Если ошибка 400 - пользователь не участник
            if (response.contains("\"error_code\":400") ||
                    response.contains("USER_NOT_PARTICIPANT")) {
                return new CheckResult(CheckStatus.SUCCESS, false);
            }

            // Ошибки прав бота
            if (response.contains("bot is not a member") ||
                    response.contains("CHAT_ADMIN_REQUIRED")) {
                return new CheckResult(CheckStatus.BOT_ERROR, false);
            }

            return new CheckResult(CheckStatus.API_ERROR, false);

        } catch (Exception e) {
            getLogger().warning("Ошибка при проверке подписки: " + e.getMessage());
            return new CheckResult(CheckStatus.ERROR, false);
        }
    }

    private Long getUserIdFromUpdates(String botToken, String telegramUsername) {
        try {
            // Получаем последние updates от бота
            String updatesUrl = "https://api.telegram.org/bot" + botToken + "/getUpdates";
            String response = makeApiRequest(updatesUrl);

            if (response == null) {
                return null;
            }

            // Ищем пользователя в updates
            Pattern pattern = Pattern.compile("\"username\":\"" + telegramUsername + "\".*?\"id\":(\\d+)");
            Matcher matcher = pattern.matcher(response);

            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }

            // Альтернативный поиск
            pattern = Pattern.compile("\"id\":(\\d+).*?\"username\":\"" + telegramUsername + "\"");
            matcher = pattern.matcher(response);

            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }

            getLogger().warning("Пользователь @" + telegramUsername + " не найден в updates бота");
            getLogger().warning("Попросите пользователя написать боту в Telegram");

        } catch (Exception e) {
            getLogger().warning("Ошибка при получении user_id: " + e.getMessage());
        }

        return null;
    }

    private String makeApiRequest(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(getConfig().getInt("timeout", 5000));
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Minecraft-Telegram-Rewards-Plugin");

            int responseCode = conn.getResponseCode();

            BufferedReader reader;
            if (responseCode == 200) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            } else {
                reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            }

            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            return response.toString();

        } catch (Exception e) {
            getLogger().warning("API request error: " + e.getMessage());
            return null;
        }
    }

    private void giveReward(Player player) {
        List<String> rewards = getConfig().getStringList("reward-commands");

        for (String reward : rewards) {
            String formattedReward = reward.replace("{player}", player.getName());

            if (formattedReward.startsWith("give ") || formattedReward.startsWith("eco ")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedReward);
            } else if (formattedReward.startsWith("msg ")) {
                String message = formattedReward.replaceFirst("msg " + player.getName() + " ", "");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formattedReward);
            }
        }
    }

    @Override
    public void onDisable() {
        savePlayerData();
        getLogger().info("Telegram Rewards plugin disabled!");
    }

    // Вспомогательные классы для результата проверки
    private enum CheckStatus { SUCCESS, ERROR, API_ERROR, BOT_ERROR }

    private class CheckResult {
        public final CheckStatus status;
        public final boolean isSubscribed;

        public CheckResult(CheckStatus status, boolean isSubscribed) {
            this.status = status;
            this.isSubscribed = isSubscribed;
        }
    }
}