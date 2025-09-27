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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DiscordService extends ListenerAdapter {
    public static class Config {
        public String botToken;
        public String channelId;   // Discord->MC ブリッジ
        public String webhookUrl;  // Webhook 宛先
        public boolean enableMessageContentIntent = true;
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path configPath;
    private final Object plugin;

    private Config config;
    private JDA jda;
    private WebhookClient webhookClient;

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

    // 起動: JDA 準備 + Webhook 準備
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
                    this.webhookClient = wcb.build(); // Webhook 経由で Embed 送信[1]
                }

                // まだ必要な定期処理があればここでスケジュール
                proxy.getScheduler().buildTask(plugin, () -> {}).repeat(60, TimeUnit.SECONDS).schedule();
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

    // Discord -> Minecraft（単一チャンネルのみ橋渡し）
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

    // ===== 送信ユーティリティ =====

    // チャット: Webhook 通常メッセージ（content のみ）、username=[%server%]%player%
    public void sendChatAsWebhook(String serverName, String playerName, String content) {
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder();
            mb.setUsername("[" + serverName + "]" + playerName);
            mb.setContent(content);
            webhookClient.send(mb.build()); // Webhook の content 投稿[1]
        } else {
            sendPlain("[" + serverName + "]" + playerName + ": " + content);
        }
    }

    // 起動/停止（既存）: ユーザー名=%server%、本文固定、色=起動:緑/停止:赤
    public void sendServerStatusViaWebhook(String serverName, boolean isUp) {
        String body = isUp ? ":white_check_mark:起動しました。" : ":octagonal_sign:停止しました。";
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder();
            mb.setUsername(serverName);
            WebhookEmbedBuilder eb = new WebhookEmbedBuilder();
            eb.setColor(isUp ? 0x2ECC71 : 0xE74C3C);
            eb.setDescription(body);
            mb.addEmbeds(eb.build());
            webhookClient.send(mb.build()); // Embed 送信[1]
        } else {
            sendPlain("[" + serverName + "] " + body);
        }
    }

    // 参加/退出: ユーザー名=Velocity、本文=✅/⛔ <player> が参加/退出しました。色=参加:緑/退出:赤
    public void sendJoinQuitViaWebhook(String playerName, boolean isJoin) {
        String body = (isJoin ? ":white_check_mark:" : ":octagonal_sign:") + playerName + (isJoin ? "が参加しました。" : "が退出しました。");
        if (webhookClient != null) {
            WebhookMessageBuilder mb = new WebhookMessageBuilder();
            mb.setUsername("Velocity");
            WebhookEmbedBuilder eb = new WebhookEmbedBuilder();
            eb.setColor(isJoin ? 0x2ECC71 : 0xE74C3C);
            eb.setDescription(body);
            mb.addEmbeds(eb.build());
            webhookClient.send(mb.build()); // Embed 送信（“□”スタイル）[1]
        } else {
            sendPlain("[Velocity] " + body);
        }
    }

    // Bot フォールバック（非同期）
    public void sendPlain(String text) {
        if (config == null || config.channelId == null) return;
        MessageChannelUnion ch = (jda != null) ? jda.getChannelById(MessageChannelUnion.class, config.channelId) : null;
        if (ch != null && ch.asTextChannel() != null) ch.asTextChannel().sendMessage(text).queue();
    }
}
