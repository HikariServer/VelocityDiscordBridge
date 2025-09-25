package jp.atsukigames.discordbridge;

import com.google.gson.Gson;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private final Map<String, Boolean> lastOnline = new HashMap<>();

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
                if (config.enableMessageContentIntent) {
                    builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
                }
                this.jda = builder.addEventListeners(this).build();
                this.jda.awaitReady();

                if (config.webhookUrl != null && !config.webhookUrl.isBlank()) {
                    WebhookClientBuilder wcb = new WebhookClientBuilder(config.webhookUrl);
                    wcb.setDaemon(true);
                    this.webhookClient = wcb.build();
                }

                proxy.getScheduler().buildTask(plugin, this::tickPing)
                        .repeat(10, TimeUnit.SECONDS).schedule();
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

    // チャット: ユーザー名=[%server%]%player%、Embed color=#2ECC71、本文=content
    public void sendChatAsWebhook(String serverName, String playerName, String content) {
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder();
            mb.setUsername("[" + serverName + "]" + playerName);
            WebhookEmbedBuilder eb = new WebhookEmbedBuilder();
            eb.setColor(0x2ECC71);
            eb.setDescription(content);
            mb.addEmbeds(eb.build());
            webhookClient.send(mb.build());
        } else {
            sendPlain("[" + serverName + "]" + playerName + ": " + content);
        }
    }

    // 起動/停止: ユーザー名=%server%、本文=固定文、色=起動#2ECC71/停止#E74C3C
    public void sendServerStatusViaWebhook(String serverName, boolean isUp) {
        String body = isUp ? ":white_check_mark:起動しました。" : ":octagonal_sign:停止しました。";
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder();
            mb.setUsername(serverName);
            WebhookEmbedBuilder eb = new WebhookEmbedBuilder();
            eb.setColor(isUp ? 0x2ECC71 : 0xE74C3C);
            eb.setDescription(body);
            mb.addEmbeds(eb.build());
            webhookClient.send(mb.build());
        } else {
            sendPlain("[" + serverName + "] " + body);
        }
    }

    // Botフォールバック（非同期）
    public void sendPlain(String text) {
        if (config == null || config.channelId == null) return;
        MessageChannel ch = (jda != null) ? jda.getChannelById(MessageChannel.class, config.channelId) : null;
        if (ch != null) ch.sendMessage(text).queue();
    }

    // 停止など確実に届けたい場合（同期）
    public void sendPlainSync(String text) {
        if (config == null) return;
        try {
            if (this.webhookClient != null) {
                WebhookMessageBuilder mb = new WebhookMessageBuilder().setContent(text);
                webhookClient.send(mb.build()).join();
                return;
            }
            if (config.channelId != null && jda != null) {
                MessageChannel ch = jda.getChannelById(MessageChannel.class, config.channelId);
                if (ch != null) ch.sendMessage(text).complete();
            }
        } catch (Exception ignored) {}
    }

    // バックエンドのオンライン/オフライン遷移検知（10秒間隔）
    private void tickPing() {
        proxy.getAllServers().forEach(rs -> {
            String name = rs.getServerInfo().getName();
            rs.ping().whenComplete((pong, err) -> {
                boolean isUp = (err == null && pong != null);
                Boolean prev = lastOnline.get(name);
                if (prev == null) {
                    lastOnline.put(name, isUp);
                    if (isUp) sendServerStatusViaWebhook(name, true);
                    return;
                }
                if (isUp != prev) {
                    lastOnline.put(name, isUp);
                    sendServerStatusViaWebhook(name, isUp);
                }
            });
        });
    }
}
