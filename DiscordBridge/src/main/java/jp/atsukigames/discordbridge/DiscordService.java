package jp.atsukigames.discordbridge;

import com.google.gson.Gson;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class DiscordService extends ListenerAdapter {
    public static class Config {
        public String botToken;
        public String channelId;
        public String webhookUrl;
        public boolean enableMessageContentIntent = true;
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path configPath;
    private final Object plugin;

    private Config config;
    private JDA jda;
    private WebhookClient webhookClient;

    // 状態保持
    private final Map<String, Boolean> lastOnline = new HashMap<>();
    private final Map<String, Integer> downStreak = new HashMap<>(); // 連続失敗回数

    public DiscordService(ProxyServer proxy, Logger logger, Path dataDirectory, Object plugin) {
        this.proxy = proxy;
        this.logger = logger;
        this.configPath = dataDirectory.resolve("config").resolve("config.json");
        this.plugin = plugin;
    }

    public void loadConfig() throws IOException {
        try (Reader r = Files.newBufferedReader(configPath)) {
            Gson gson = new Gson();
            this.config = gson.fromJson(r, Config.class);
        }
    }

    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (this.config == null) loadConfig();
                JDABuilder builder = JDABuilder.createDefault(config.botToken);
                if (config.enableMessageContentIntent) builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
                this.jda = builder.addEventListeners(this).build();
                this.jda.awaitReady();

                if (config.webhookUrl != null && !config.webhookUrl.isBlank()) {
                    WebhookClientBuilder wcb = new WebhookClientBuilder(config.webhookUrl);
                    wcb.setDaemon(true);
                    this.webhookClient = wcb.build();
                }

                // 10秒ごとに全 RegisteredServer を監視（タイムアウト＋連続失敗で停止判定）
                proxy.getScheduler().buildTask(plugin, this::tickPing).repeat(10, TimeUnit.SECONDS).schedule();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void stop() {
        if (this.jda != null) this.jda.shutdownNow();
        if (this.webhookClient != null) this.webhookClient.close();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (config == null || config.channelId == null) return;
        if (!event.getChannel().getId().equals(config.channelId)) return;

        String display = (event.getMember() != null) ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        String content = event.getMessage().getContentDisplay();

        proxy.getScheduler().buildTask(plugin, () -> {
            Component msg = Component.text("[Discord]", NamedTextColor.GREEN)
                    .append(Component.text(" " + display + ": " + content, NamedTextColor.WHITE));
            for (Player p : proxy.getAllPlayers()) p.sendMessage(msg);
        }).schedule();
    }

    // Minecraft -> Discord（チャット）
    public void sendChatAsWebhook(String serverName, String playerName, String content) {
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder();
            mb.setUsername("[" + serverName + "]" + playerName);
            mb.setContent(content);
            webhookClient.send(mb.build());
        } else {
            sendPlain("[" + serverName + "]" + playerName + ": " + content);
        }
    }

    // 起動/停止 Embed
    public void sendServerStatusViaWebhook(String serverName, boolean isUp) {
        String body = isUp ? ":white_check_mark:起動しました。" : ":octagonal_sign:停止しました。";
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder().setUsername(serverName);
            WebhookEmbedBuilder eb = new WebhookEmbedBuilder()
                    .setColor(isUp ? 0x2ECC71 : 0xE74C3C)
                    .setDescription(body);
            mb.addEmbeds(eb.build());
            webhookClient.send(mb.build());
        } else {
            sendPlain("[" + serverName + "] " + body);
        }
    }

    // 参加/退出 Embed
    public void sendJoinQuitViaWebhook(String playerName, boolean isJoin) {
        String body = (isJoin ? ":white_check_mark:" : ":octagonal_sign:") + playerName + (isJoin ? "が参加しました。" : "が退出しました。");
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder().setUsername("Velocity");
            WebhookEmbedBuilder eb = new WebhookEmbedBuilder()
                    .setColor(isJoin ? 0x2ECC71 : 0xE74C3C)
                    .setDescription(body);
            mb.addEmbeds(eb.build());
            webhookClient.send(mb.build());
        } else {
            sendPlain("[Velocity] " + body);
        }
    }

    public void sendPlain(String text) {
        if (config == null || config.channelId == null) return;
        MessageChannelUnion ch = (jda != null) ? jda.getChannelById(MessageChannelUnion.class, config.channelId) : null;
        if (ch != null && ch.asTextChannel() != null) ch.asTextChannel().sendMessage(text).queue();
    }

    // 定期 ping 監視（3秒タイムアウト、2連続失敗で停止判定）
    private void tickPing() {
        proxy.getAllServers().forEach(rs -> {
            final String name = rs.getServerInfo().getName();
            rs.ping()
              .orTimeout(3, TimeUnit.SECONDS)
              .handle((pong, err) -> {
                  boolean isUp = (err == null && pong != null);
                  Boolean prev = lastOnline.get(name);

                  if (isUp) {
                      downStreak.put(name, 0);
                      if (prev == null) {
                          lastOnline.put(name, true);
                          sendServerStatusViaWebhook(name, true);
                      } else if (!prev) {
                          lastOnline.put(name, true);
                          sendServerStatusViaWebhook(name, true);
                      }
                  } else {
                      int streak = downStreak.getOrDefault(name, 0) + 1;
                      downStreak.put(name, streak);
                      if (streak >= 2) { // 2回連続でオフと見なす
                          if (prev == null) {
                              lastOnline.put(name, false);
                              sendServerStatusViaWebhook(name, false);
                          } else if (prev) {
                              lastOnline.put(name, false);
                              sendServerStatusViaWebhook(name, false);
                          }
                      }
                  }
                  return null;
              });
        });
    }
}
