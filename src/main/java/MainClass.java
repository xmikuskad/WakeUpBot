import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
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

    final String roleMsg = "854354694733692979";
    final String roleRoom = "854352780394102804";

    boolean isMoving = false;
    static boolean isDebugging = false;

    Thread thread = null;
    WakeUpBumper wakeUpBumper = null;

    final String valo = "\uD83D\uDD2B";
    final String rocket = "\uD83D\uDE0E";
    final String minecraft = "⛏";
    final String rust = "\uD83C\uDFF9";
    final String lol = "\uD83D\uDE42";

    final String valoRole = "854352998595559465";
    final String rocketRole = "854353349788172308";
    final String minecraftRole = "854354125407387739";
    final String rustRole = "854354259998933005";
    final String lolRole = "854353530172866568";

    public static void main(String[] args) throws LoginException {
        String token = System.getenv("API_TOKEN");
        if(token == null) {
            token = getBotToken();
        }
        if(token == null) {
            System.out.println("Env variable not set and config not found");
            return;
        }
        JDABuilder builder = JDABuilder.createDefault(token);
        builder.setActivity(Activity.playing("Švanda :) Try ,help"));

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
    public void onMessageReactionAdd(@NotNull MessageReactionAddEvent event) {
        super.onMessageReactionAdd(event);

        if(Objects.requireNonNull(event.getUser()).isBot()) return;
        if(event.getChannel().getId().equals(roleRoom) && event.getReaction().getMessageId().equals(roleMsg)) {
            String reaction = event.getReaction().getReactionEmote().getAsReactionCode();
            String roleId = getRoleId(reaction);

            if(!roleId.isEmpty()) {
                event.getGuild().addRoleToMember(event.getUserId(), Objects.requireNonNull(event.getJDA().getRoleById(roleId))).queue();
            }
        }
    }

    @Override
    public void onMessageReactionRemove(@NotNull MessageReactionRemoveEvent event) {
        super.onMessageReactionRemove(event);

        if(Objects.requireNonNull(event.getUser()).isBot()) return;

        if(event.getChannel().getId().equals(roleRoom) && event.getReaction().getMessageId().equals(roleMsg)) {
            String reaction = event.getReaction().getReactionEmote().getAsReactionCode();
            String roleId = getRoleId(reaction);

            try {
                if (!roleId.isEmpty()) {
                    event.getGuild().removeRoleFromMember(event.getUserId(), Objects.requireNonNull(event.getJDA().getRoleById(roleId))).queue();
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public String getRoleId(String reaction) {
        String roleId = "";

        if(reaction.equals(rocket)) {
            roleId = rocketRole;
        }
        if(reaction.equals(lol)) {
            roleId = lolRole;
        }
        if(reaction.equals(rust)) {
            roleId = rustRole;
        }
        if(reaction.equals(valo)) {
            roleId = valoRole;
        }
        if(reaction.equals(minecraft)) {
            roleId = minecraftRole;
        }
        return roleId;
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

        if(!msg.isEmpty() && msg.startsWith(",wake")) {
            if(!areChecksFine(event)) {
                return;
            }

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

            // Find our server
            for(Guild guild:event.getJDA().getGuilds()) {
                if(Objects.equals(guild.getId(),arasidy)) {
                    server = guild;
                }
            }
            if(server == null) {
                isMoving = false;
                return;
            }
            // Find member to move
            Member member = server.getMember(userToMove);
            if(member == null) {
                event.getChannel().sendMessage("User not in voice channel.").queue();
                isMoving = false;
                return;
            }
            List<VoiceChannel> channels = server.getVoiceChannels();
            VoiceChannel initialChannel = null;

            int emptyChannels = 0;
            // Count if we have enough channels to move him
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

            // Stop previous moving
            if(wakeUpBumper!= null) {
                if(wakeUpBumper.isRunning())
                    wakeUpBumper.stopRunnable();
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Start moving on new thread to prevent ignoring ,stop
            wakeUpBumper = new WakeUpBumper(channels,member,server,count,initialChannel,this);
            thread = new Thread(wakeUpBumper);
            thread.start();
        }

        if(!msg.isEmpty() && msg.startsWith(",stop")) {
            if(!areChecksFine(event)) {
                return;
            }

            // Stop moving user
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
            if(!areChecksFine(event)) {
                return;
            }

            // Return basic move guide
            event.getChannel().sendMessage(",wake @tag X - moves user X times (max 10) or 10 if not specified\n" +
                    "Example: ,wake <@850777293739786280> 5\n"+
                    ",stop - stop moving user").queue();
        }

        /*if(!msg.isEmpty() && msg.startsWith(",show")) {
            if(!areChecksFine(event)) {
                return;
            }

            // Return basic move guide
            event.getChannel().sendMessage("React to give yourself a role\n" +
                    ":sunglasses: : Rocket league\n"+
                    ":slight_smile: : League of legends\n" +
                    ":gun: : Valorant\n" +
                    ":pick: : Minecraft\n" +
                    ":bow_and_arrow: : Rust").queue((message) -> {
                event.getChannel().addReactionById(message.getId(),rocket).queue();
                event.getChannel().addReactionById(message.getId(),lol).queue();
                event.getChannel().addReactionById(message.getId(),valo).queue();
                event.getChannel().addReactionById(message.getId(),minecraft).queue();
                event.getChannel().addReactionById(message.getId(),rust).queue();
            });
        }*/

    }

    public boolean areChecksFine(MessageReceivedEvent event){
        if(isDebugging && !Objects.equals(event.getChannel().getId(),testRoom)) {
            return false;
        }

        if(!isDebugging && !Objects.equals(event.getChannel().getId(),botRoomId) && !Objects.equals(event.getChannel().getId(),testRoom)) {
            event.getChannel().sendMessage("Wrong room noob. :pinching_hand: ").queue();
            return false;
        }

        return true;
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

    public boolean moveUser(VoiceChannel vc) throws InterruptedException {
        if (!vc.getMembers().isEmpty()) {
            return false;
        }
        server.moveVoiceMember(member, vc).queue();
        Thread.sleep(DELAY);

        return true;
    }
}
