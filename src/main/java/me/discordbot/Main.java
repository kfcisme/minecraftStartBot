package me.discordbot;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;

import javax.security.auth.login.LoginException;
import java.io.*;

public class Main extends ListenerAdapter{
    private Process serverProcess = null;
    private final String serverDirectory = "your_path_to_server";
    private final String startCommand = "java -Xmx8G -Xms1G -jar server.jar nogui";

    public static void main(String[] args) throws LoginException {
        String token = "your_discordbot_token";
        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Main())
                .build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        System.out.println("收到訊息: " + event.getMessage().getContentRaw());

        String content = event.getMessage().getContentRaw().trim().toLowerCase();
        if (content.equals("!panel")) {
            sendControlPanel(event.getChannel());
        }
    }

    private void sendControlPanel(MessageChannel channel) {
        channel.sendMessage("🖥 **Minecraft 控制台**")
                .setActionRow(
                        Button.success("start_server", "啟動伺服器"),
                        Button.danger("stop_server", "停止伺服器"),
                        Button.secondary("status_server", "狀態查詢")
                ).queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();

        switch (id) {
            case "start_server" -> handleStart(event);
            case "stop_server" -> handleStop(event);
            case "status_server" -> handleStatus(event);
        }
    }

    private void handleStart(ButtonInteractionEvent event) {
        if (serverProcess != null && serverProcess.isAlive()) {
            event.reply("⚠️ 伺服器已在運行中。").setEphemeral(true).queue();
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", startCommand);
            pb.directory(new File(serverDirectory));
            pb.redirectErrorStream(true);
            serverProcess = pb.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[伺服器輸出] " + line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            event.reply("✅ 伺服器已啟動！").setEphemeral(true).queue();
        } catch (IOException e) {
            e.printStackTrace();
            event.reply("❌ 無法啟動伺服器：" + e.getMessage()).setEphemeral(true).queue();
        }
    }


    private void handleStop(ButtonInteractionEvent event) {
        if (serverProcess != null && serverProcess.isAlive()) {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(serverProcess.getOutputStream()))) {
                writer.write("stop\n");
                writer.flush();
                event.reply("🛑 已傳送 stop 指令，伺服器將關閉。").setEphemeral(true).queue();
            } catch (IOException e) {
                e.printStackTrace();
                event.reply("❌ 傳送 stop 指令失敗：" + e.getMessage()).setEphemeral(true).queue();
            }
        } else {
            event.reply("⚠️ 伺服器尚未啟動。").setEphemeral(true).queue();
        }
    }


    private void handleStatus(ButtonInteractionEvent event) {
        boolean running = serverProcess != null && serverProcess.isAlive();
        String msg = running ? "🟢 伺服器正在運行。" : "🔴 伺服器未啟動。";
        event.reply(msg).setEphemeral(true).queue();
    }
}