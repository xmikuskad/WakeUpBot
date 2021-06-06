import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import javax.security.auth.login.LoginException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

public class MainClass extends ListenerAdapter {

    final String testRoom = "850778478797258815";
    final String botRoomId = "826118900743012422";

    final String arasidy = "677581027379511346";

    boolean isMoving = false;
    static boolean isDebugging = false;

    Thread thread = null;
    WakeUpBumper wakeUpBumper = null;


    public static void main(String[] args) throws LoginException {
        String token = System.getenv("API_TOKEN");
        if(isDebugging) {
            token = getBotToken();
        }
        if(token == null) {
            System.out.println("Env variable not set");
            return;
        }
        JDABuilder builder = JDABuilder.createDefault(token);
        builder.setActivity(Activity.playing("Wake up people :) Try ,help"));

        builder.addEventListeners(new MainClass());
        builder.build();
    }

    // Config file is not on github!
    public static String getBotToken() {
        Properties prop = new Properties();
        String configName = "config.properties";
        final String TOKEN_NAME = "token";
        // Config should have one item which is: token=xyz
        InputStream is;
        try {
            is = new FileInputStream(configName);
            prop.load(is);
            return prop.getProperty(TOKEN_NAME);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if(event.getAuthor().isBot()) {
            return;
        }

        String msg = event.getMessage().getContentRaw();
        if(!msg.isEmpty() && msg.startsWith(",debugging")) {
            Long debugStatus = getMoveCount(msg);
            if(debugStatus!=null) {
                isDebugging = debugStatus == 1;
            }

            event.getChannel().sendMessage("Debug status set to "+isDebugging).queue();
            return;
        }

        if(isDebugging && !Objects.equals(event.getChannel().getId(),testRoom)) {
            event.getChannel().sendMessage("Maintenance, try again later").queue();
            return;
        }

        if(!isDebugging && !Objects.equals(event.getChannel().getId(),botRoomId) && !Objects.equals(event.getChannel().getId(),testRoom)) {
            event.getChannel().sendMessage("Wrong room noob. :)").queue();
            return;
        }

        if(!msg.isEmpty() && msg.startsWith(",wake")) {
            // Get user
            Long id = getUserId(msg);
            if(id == null) {
                event.getChannel().sendMessage("User not found! Did you tag him?").queue();
                return;
            }

            // Check if user is not being moved
            if(isMoving) {
                event.getChannel().sendMessage("User is already being moved").queue();
                return;
            }
            isMoving = true;

            User userToMove = event.getJDA().retrieveUserById(id).complete();
            Long count = getMoveCount(msg);

            // Get voice channels
            Guild server = null;

            for(Guild guild:event.getJDA().getGuilds()) {
                if(Objects.equals(guild.getId(),arasidy)) {
                    server = guild;
                }
            }
            if(server == null) {
                isMoving = false;
                return;
            }
            Member member = server.getMember(userToMove);
            if(member == null) {
                isMoving = false;
                return;
            }
            List<VoiceChannel> channels = server.getVoiceChannels();
            VoiceChannel initialChannel = null;

            int emptyChannels = 0;
            for(VoiceChannel channel:channels) {
                Set<Member> members = new HashSet<>(channel.getMembers());
                if(members.isEmpty())
                    emptyChannels++;
                if(members.contains(member)) {
                    initialChannel = channel;
                }
            }
            if(emptyChannels <=0) {
                event.getChannel().sendMessage("Not enough empty voice channels to move user!").queue();
                isMoving = false;
                return;
            }

            if(wakeUpBumper!= null) {
                if(wakeUpBumper.isRunning())
                    wakeUpBumper.stopRunnable();
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            wakeUpBumper = new WakeUpBumper(channels,member,server,count,initialChannel,this);
            thread = new Thread(wakeUpBumper);
            thread.start();
        }

        if(!msg.isEmpty() && msg.startsWith(",stop")) {
            if(wakeUpBumper!= null) {
                if(wakeUpBumper.isRunning())
                    wakeUpBumper.stopRunnable();
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            isMoving = false;
        }

        if(!msg.isEmpty() && msg.startsWith(",help")) {
            event.getChannel().sendMessage(",wake @tag X - moves user X times (max 10) or 10 if not specified\n" +
                    ",stop - stop moving user").queue();
        }

    }

    public void stopMoving() {
        isMoving = false;
    }

    public Long getUserId(String msg) {
        String[] content = msg.split("@!");
        if(content.length <=1) {
            return null;
        }
        String idString = content[1].split(">")[0];
        long id;
        try {
            id = Long.parseLong(idString);
        } catch (Exception e) {
            return null;
        }
        return id;
    }

    public Long getMoveCount(String msg) {
        String[] content = msg.split(" ");
        if(content.length <=2) {
            return null;
        }
        long count;
        try {
            count = Long.parseLong(content[2]);
        } catch (Exception e) {
            return null;
        }
        return count;
    }

}

// Move user on new thread
class WakeUpBumper implements Runnable {

    private final List<VoiceChannel> voiceChannels;
    private final VoiceChannel initialVoiceChannel;
    private final Member member;
    private final Guild server;
    private final MainClass mainClass;

    private boolean isRunning = true;
    private final int DELAY = 1000;
    private final Long MAX_MOVE_COUNT = 10L;
    private Long count;

    public WakeUpBumper(List<VoiceChannel> voiceChannels, Member member, Guild server, Long count, VoiceChannel initialVoiceChannel, MainClass mainClass) {
        this.voiceChannels = voiceChannels;
        this.member = member;
        this.server = server;
        if(count == null || count < 0 || count > MAX_MOVE_COUNT) {
            this.count = MAX_MOVE_COUNT;
        }
        else {
            this.count = count;
        }
        this.initialVoiceChannel = initialVoiceChannel;
        this.mainClass = mainClass;
    }

    public void stopRunnable(){
        isRunning = false;
    }

    public boolean isRunning() { return isRunning;    }

    @Override
    public void run() {

        try {
            while (isRunning && count > 0) {
                int movedCount = 0;
                for (VoiceChannel vc : voiceChannels) {
                    if (moveUser(vc)) {
                        movedCount++;
                        count--;
                        if (count <= 0) break;
                    }
                    if (!isRunning)
                        break;
                }
                // Prevent looping when there is <1 empty channel
                if (movedCount == 0)
                    isRunning = false;
            }
            isRunning = false;

            // Return to starting channel
            server.moveVoiceMember(member, initialVoiceChannel).queue();
        } catch (Exception e) {
            isRunning = false;
            e.printStackTrace();
        }
        mainClass.stopMoving();
    }

    public boolean moveUser(VoiceChannel vc) throws Exception {
        if (!vc.getMembers().isEmpty()) {
            return false;
        }
        try {
            server.moveVoiceMember(member, vc).queue();
        } catch (Exception e) {
            // To force stop execution when user DC or something
            throw new Exception("Some problem, idk");
        }
        try {
            Thread.sleep(DELAY);
            return true;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return true;
    }
}
