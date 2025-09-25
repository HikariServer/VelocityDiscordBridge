package jp.atsukigames.discordbridge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiscordBridgeVelocity {
    private final ProxyServer proxy;
    private final org.slf4j.Logger logger;
    private final Path dataDirectory;

    private DiscordService discord;

    @Inject
    public DiscordBridgeVelocity(ProxyServer proxy, org.slf4j.Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            Files.createDirectories(dataDirectory.resolve("config"));
        } catch (Exception ignored) {}
        this.discord = new DiscordService(proxy, java.util.logging.Logger.getLogger("DiscordBridge"), dataDirectory, this);
        discord.start().whenComplete((v, ex) -> {
            if (ex == null) {
                // プロキシ起動（Embed緑）
                discord.sendServerStatusViaWebhook("Velocity", true);
            } else {
                logger.error("Discord bot failed to start: {}", ex.getMessage());
            }
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        try {
            if (discord != null) {
                // プロキシ停止は同期送信（Bot/Webhook終了前に確実に送る）
                discord.sendPlainSync(":octagonal_sign:Velocityが停止しました。");
            }
        } catch (Exception ignored) {}
        if (discord != null) {
            discord.stop();
        }
    }

    // バックエンド登録（起動）
    @Subscribe
    public void onServerRegistered(ServerRegisteredEvent e) {
        String name = e.registeredServer().getServerInfo().getName();
        if (discord != null) {
            discord.sendServerStatusViaWebhook(name, true);
        }
    }

    // バックエンド登録解除（停止）
    @Subscribe
    public void onServerUnregistered(ServerUnregisteredEvent e) {
        String name = e.unregisteredServer().getServerInfo().getName();
        if (discord != null) {
            discord.sendServerStatusViaWebhook(name, false);
        }
    }

    // プロキシ参加
    @Subscribe
    public void onJoin(PostLoginEvent e) {
        Player p = e.getPlayer();
        String msg = p.getUsername() + "が参加しました。";
        if (discord != null) {
            discord.sendPlain(msg);
        }
    }

    // プロキシ退出
    @Subscribe
    public void onQuit(DisconnectEvent e) {
        Player p = e.getPlayer();
        String msg = p.getUsername() + "が退出しました。";
        if (discord != null) {
            discord.sendPlain(msg);
        }
    }

    // サーバー移動通知は無効化（空実装）
    @Subscribe
    public void onServerMove(ServerPostConnectEvent e) {
        // disabled by design
    }

    // ゲーム内チャット → Discord（Webhookで[%server%]%player%名義）
    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String serverName = p.getCurrentServer()
                .map(cs -> cs.getServerInfo().getName())
                .orElse("unknown");
        if (discord != null) {
            discord.sendChatAsWebhook(serverName, p.getUsername(), e.getMessage());
        }
    }
}
