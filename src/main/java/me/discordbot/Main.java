package me.discordbot;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;

import javax.security.auth.login.LoginException;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Main extends ListenerAdapter {

    private final Path mcRoot = Paths.get("C:\\Users\\hsu96\\OneDrive\\Desktop\\worldinajar");

    private final Map<Long, Process> runningByChannel = new ConcurrentHashMap<>();

    private final Map<Long, Path> selectedDirByChannel = new ConcurrentHashMap<>();

    public static void main(String[] args) throws LoginException {
        String token = "你的Token";
        if (token == null || token.isBlank()) {
            throw new RuntimeException("請設定 DISCORD_TOKEN");
        }
        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Main())
                .build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String content = event.getMessage().getContentRaw().trim();
        if (content.equalsIgnoreCase("!panel")) {
            sendControlPanel(event.getChannel());
        }
    }

    private void sendControlPanel(MessageChannel channel) {
        List<Path> serverDirs = listServerDirs(mcRoot);

        StringSelectMenu menu = buildServersMenu(serverDirs);
        channel.sendMessage("🖥 **Minecraft 控制台**（請先在選單選擇要操作的伺服器資料夾）")
                .setComponents(
                        ActionRow.of(menu),
                        ActionRow.of(
                                Button.success("start_server", "啟動伺服器"),
                                Button.danger("stop_server", "停止伺服器"),
                                Button.primary("op_player", "給OP"),
                                Button.secondary("status_server", "狀態查詢"),
                                Button.primary("refresh_panel", "刷新")
                        )
                ).queue();
    }

    private List<Path> listServerDirs(Path root) {
        try {
            if (!Files.isDirectory(root)) return List.of();
            try (var s = Files.list(root)) {
                return s.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }

    private StringSelectMenu buildServersMenu(List<Path> dirs) {
        var builder = StringSelectMenu.create("server_select")
                .setPlaceholder(dirs.isEmpty() ? "（找不到任何資料夾）" : "選擇要操作的伺服器資料夾")
                .setRequiredRange(1, 1);

        int limit = Math.min(25, dirs.size());
        for (int i = 0; i < limit; i++) {
            Path p = dirs.get(i);
            String label = p.getFileName().toString();
            // value
            builder.addOption(label, p.toAbsolutePath().toString());
        }
        return builder.build();
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals("server_select")) return;

        String selectedPath = event.getValues().get(0);
        Path dir = Paths.get(selectedPath);

        if (!Files.isDirectory(dir)) {
            event.reply("資料夾不存在：`" + selectedPath + "`").setEphemeral(true).queue();
            return;
        }
        long channelId = event.getChannel().getIdLong();
        selectedDirByChannel.put(channelId, dir);

        event.reply("已選擇資料夾：`" + dir.getFileName() + "`").setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        switch (id) {
            case "start_server" -> handleStart(event, event.getInteraction().getChannel());
            case "stop_server" -> handleStop(event, event.getInteraction().getChannel());
            case "status_server" -> handleStatus(event, event.getInteraction().getChannel());
            case "refresh_panel" -> {
                event.deferEdit().queue();
                sendControlPanel(event.getChannel());
            }
            case "op_player" -> {
                long chId = event.getChannel().getIdLong();
                Process proc = runningByChannel.get(chId);
                if (proc == null || !proc.isAlive()) {
                    event.reply("伺服器未啟動，無法執行指令。").setEphemeral(true).queue();
                    return;
                }
                TextInput playerName = TextInput.create("player_name", "玩家 ID", TextInputStyle.SHORT)
                        .setPlaceholder("輸入要加 OP 的玩家名稱")
                        .setRequired(true)
                        .build();

                Modal modal = Modal.create("op_modal", "給OP")
                        .addComponents(ActionRow.of(playerName))
                        .build();

                event.replyModal(modal).queue();
            }
        }
    }

    private void handleStart(ButtonInteractionEvent event, MessageChannelUnion channel) {
        long chId = channel.getIdLong();

        if (runningByChannel.containsKey(chId) && runningByChannel.get(chId).isAlive()) {
            event.reply("伺服器運行中。").setEphemeral(true).queue();
            return;
        }

        Path dir = selectedDirByChannel.get(chId);
        if (dir == null) {
            event.reply("請先從選單選擇伺服器資料夾！").setEphemeral(true).queue();
            return;
        }

        Optional<List<String>> startCmd = resolveStartCommand(dir);
        if (startCmd.isEmpty()) {
            event.reply("無法在 `" + dir.getFileName() + "` 找到可用的啟動方式（start.bat / start.sh / .jar）").setEphemeral(true).queue();
            return;
        }

        try {
            ProcessBuilder pb = buildProcess(startCmd.get(), dir);
            Process proc = pb.start();
            runningByChannel.put(chId, proc);

            new Thread(() -> pipeOutput(proc, "[伺服器輸出][" + dir.getFileName() + "] ")).start();

//            event.reply("伺服器已啟動（`" + dir.getFileName() + "`）！").setEphemeral(true).queue();
            User user = event.getInteraction().getUser();
            channel.sendMessage(user.getAsMention() + " 啟動了伺服器 **" + dir.getFileName() + "**").queue();
        } catch (IOException e) {
            e.printStackTrace();
            event.reply("無法啟動伺服器：" + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private Optional<List<String>> resolveStartCommand(Path dir) {
        Path bat = dir.resolve("start.bat");
        Path sh  = dir.resolve("start.sh");
        try {
            if (Files.isRegularFile(bat)) {
                return Optional.of(List.of("cmd", "/c", "start.bat"));
            }
            if (Files.isRegularFile(sh)) {
                return Optional.of(List.of("bash", "start.sh"));
            }

            List<Path> jars;
            try (var s = Files.list(dir)) {
                jars = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(
                                (Path p) -> scoreJarName(p.getFileName().toString())
                        ).reversed())
                        .collect(Collectors.toList());
            }

            if (jars.isEmpty()) return Optional.empty();

            Path top = jars.get(0);
            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            cmd.add("-Xmx16G");
            cmd.add("-Xms1G");
            cmd.add("-jar");
            cmd.add(top.getFileName().toString());
            cmd.add("nogui");
            return Optional.of(cmd);

        } catch (IOException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private int scoreJarName(String name) {
        String n = name.toLowerCase();
        int score = 0;
        if (n.contains("server")) score += 5;
        if (n.contains("fabric")) score += 4;
        if (n.contains("paper"))  score += 4;
        if (n.contains("forge"))  score += 3;
        if (n.contains("vanilla"))score += 2;
        if (n.contains("spigot")) score += 4;

        if (n.contains("launcher"))score -= 1;
        if (n.contains("installer"))score -= 2;
        if (n.contains("dev")) score -= 1;
        if (n.contains("client")) score -= 3;
        score += Math.max(0, 50 - n.length()) / 10;
        return score;
    }


    private ProcessBuilder buildProcess(List<String> cmd, Path dir) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        return pb;
    }

    private void pipeOutput(Process proc, String prefix) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(prefix + line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleStop(ButtonInteractionEvent event, MessageChannelUnion channel) {
        long chId = channel.getIdLong();
        Process proc = runningByChannel.get(chId);

        if (proc != null && proc.isAlive()) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()))) {
                writer.write("stop\n");
                writer.flush();
//                event.reply("已傳送 `stop` 指令").setEphemeral(true).queue();
                User user = event.getInteraction().getUser();

                Path dir = selectedDirByChannel.get(chId);
                String name = dir != null ? dir.getFileName().toString() : "(未命名)";
                channel.sendMessage(user.getAsMention() + " 關閉了伺服器 **" + name + "**").queue();
            } catch (IOException e) {
                e.printStackTrace();
                event.reply("`stop` 指令失敗：" + e.getMessage()).setEphemeral(true).queue();
            }
        } else {
            event.reply("沒有正在運行的伺服器").setEphemeral(true).queue();
        }
    }

    private void handleStatus(ButtonInteractionEvent event, MessageChannelUnion channel) {
        long chId = channel.getIdLong();
        Process proc = runningByChannel.get(chId);
        boolean running = proc != null && proc.isAlive();

        String msg = running ? "伺服器正在運行。" : "伺服器未啟動。";

        Path dir = selectedDirByChannel.get(chId);
        String name = dir != null ? dir.getFileName().toString() : "（尚未選擇資料夾）";
        channel.sendMessage("目前 " + msg + " 目標：「" + name + "」").queue();
    }
    @Override


    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().equals("op_modal")) {
            String playerName = event.getValue("player_name").getAsString();
            long chId = event.getChannel().getIdLong();
            Process proc = runningByChannel.get(chId);

            if (proc != null && proc.isAlive()) {
                try {
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
                    writer.write("op " + playerName + "\n");
                    writer.flush();

                    event.reply("已發送指令：`/op " + playerName + "`").queue();
                } catch (IOException e) {
                    event.reply("指令失敗：" + e.getMessage()).setEphemeral(true).queue();
                }
            } else {
                event.reply("伺服器已離線").setEphemeral(true).queue();
            }
        }
    }
}
