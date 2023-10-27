package org.cloudburstmc.proxypass;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.google.gson.JsonParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.raphimc.mcauth.MinecraftAuth;
import net.raphimc.mcauth.step.bedrock.StepMCChain;
import net.raphimc.mcauth.step.bedrock.StepPlayFabToken;
import net.raphimc.mcauth.step.msa.StepMsaDeviceCode;
import net.raphimc.mcauth.util.MicrosoftConstants;
import org.apache.http.impl.client.CloseableHttpClient;
import org.cloudburstmc.nbt.*;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v622.Bedrock_v622;
import org.cloudburstmc.protocol.bedrock.codec.v618.Bedrock_v618;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.proxypass.network.bedrock.session.Account;
import org.cloudburstmc.proxypass.network.bedrock.session.ProxyClientSession;
import org.cloudburstmc.proxypass.network.bedrock.session.ProxyServerSession;
import org.cloudburstmc.proxypass.network.bedrock.session.UpstreamPacketHandler;
import org.cloudburstmc.proxypass.network.bedrock.util.BlockPaletteUtils;
import org.cloudburstmc.proxypass.network.bedrock.util.NbtBlockDefinitionRegistry;
import org.cloudburstmc.proxypass.network.bedrock.util.UnknownBlockDefinitionRegistry;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Log4j2
@Getter
public class ProxyPass {
    public static final ObjectMapper JSON_MAPPER;
    public static final YAMLMapper YAML_MAPPER = (YAMLMapper) new YAMLMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    public static final String SERVER_MINECRAFT_VERSION;
    public static final String CLIENT_MINECRAFT_VERSION;

    // HEHE THIS CANT GO WRONG
    public static final BedrockCodec SERVER_CODEC = Bedrock_v618.CODEC; // Hosting a v618 server
    public static final BedrockCodec CLIENT_CODEC = Bedrock_v622.CODEC; // Using a v622 client

    public static final int SERVER_PROTOCOL_VERSION = SERVER_CODEC.getProtocolVersion();
    public static final int CLIENT_PROTOCOL_VERSION = CLIENT_CODEC.getProtocolVersion();
    private static final BedrockPong ADVERTISEMENT = new BedrockPong()
            .edition("MCPE")
            .gameType("Survival")
            .version(ProxyPass.SERVER_MINECRAFT_VERSION)
            .protocolVersion(ProxyPass.SERVER_PROTOCOL_VERSION)
            .motd("VersionPass")
            .playerCount(0)
            .maximumPlayerCount(20)
            .subMotd("https://github.com/CloudburstMC/ProxyPass")
            .nintendoLimited(false);
    private static final DefaultPrettyPrinter PRETTY_PRINTER;
    public static Map<Integer, String> legacyIdMap = new HashMap<>();

    static {
        PRETTY_PRINTER = new DefaultPrettyPrinter() {
            @Override
            public DefaultPrettyPrinter createInstance() {
                return this;
            }

            @SuppressWarnings("NullableProblems")
            @Override
            public void writeObjectFieldValueSeparator(JsonGenerator generator) throws IOException {
                generator.writeRaw(": ");
            }
        };

        DefaultIndenter indenter = new DefaultIndenter("    ", "\n");
        PRETTY_PRINTER.indentArraysWith(indenter);
        PRETTY_PRINTER.indentObjectsWith(indenter);

        JSON_MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).setDefaultPrettyPrinter(PRETTY_PRINTER);
        SERVER_MINECRAFT_VERSION = SERVER_CODEC.getMinecraftVersion();
        CLIENT_MINECRAFT_VERSION = CLIENT_CODEC.getMinecraftVersion();
    }

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    private final Set<Channel> clients = ConcurrentHashMap.newKeySet();
    @Getter(AccessLevel.NONE)
    private final Set<Class<?>> ignoredPackets = Collections.newSetFromMap(new IdentityHashMap<>());
    private Channel server;
    private int maxClients = 0;
    private boolean onlineMode = false;
    private boolean saveAuthDetails = false;
    private InetSocketAddress targetAddress;
    private InetSocketAddress proxyAddress;
    private Configuration configuration;
    private Path baseDir;
    private Path sessionsDir;
    private Path dataDir;
    private DefinitionRegistry<BlockDefinition> serverBlockDefinitions; // Block definitions FROM the server (v622)
    private DefinitionRegistry<BlockDefinition> clientBlockDefinitions; // Block definitions FROM the client (v618)

    Map<Integer, Integer> serverBlockPaletteMap;
    Map<Integer, Integer> clientBlockPaletteMap;

    Map<Integer, Integer> RIDReplacementsClientToServer = new HashMap<>();
    Map<Integer, Integer> RIDReplacementsServerToClient = new HashMap<>();

    private static Account account;

    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        ProxyPass proxy = new ProxyPass();
        try {
            proxy.boot();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void boot() throws IOException {
        log.info("Loading configuration...");
        Path configPath = Paths.get(".").resolve("config.yml");
        if (Files.notExists(configPath) || !Files.isRegularFile(configPath)) {
            Files.copy(ProxyPass.class.getClassLoader().getResourceAsStream("config.yml"), configPath, StandardCopyOption.REPLACE_EXISTING);
        }

        configuration = Configuration.load(configPath);

        proxyAddress = configuration.getProxy().getAddress();
        targetAddress = configuration.getDestination().getAddress();
        maxClients = configuration.getMaxClients();
        onlineMode = configuration.isOnlineMode();
        saveAuthDetails = configuration.isSaveAuthDetails();

        configuration.getIgnoredPackets().forEach(s -> {
            try {
                ignoredPackets.add(Class.forName("org.cloudburstmc.protocol.bedrock.packet." + s));
            } catch (ClassNotFoundException e) {
                log.warn("No packet with name {}", s);
            }
        });

        baseDir = Paths.get(".").toAbsolutePath();
        sessionsDir = baseDir.resolve("sessions");
        dataDir = baseDir.resolve("data");
        Files.createDirectories(sessionsDir);
        Files.createDirectories(dataDir);

        if (onlineMode) {
            log.info("Online mode is enabled. Starting auth process...");
            try {
                account = getAuthenticatedAccount(saveAuthDetails);
                log.info("Successfully logged in as {}", account.mcChain().displayName());
            } catch (Exception e) {
                log.error("Setting to offline mode due to failure to get login chain:", e);
                onlineMode = false;
            }
        }

        // Load block palette, if it exists (taken from GeyserMC)
        Object serverBlockDefinitionsNBT = this.loadGzipNBT("block_palette.1_20_40.nbt");
        Object clientBlockDefinitionsNBT = this.loadGzipNBT("block_palette.1_20_30.nbt");

        this.serverBlockPaletteMap = new HashMap<>();
        this.clientBlockPaletteMap = new HashMap<>();

        /*
         * CardinalFix basically fakes this without actually fixing everything
         * Since we have two seperate replacement maps...
         * 
         * serverBlockDefinitions is changed so that it uses the client's expected version (facing_direction)
         * clientBlockDefinitions is changed so that it uses the server's expected version (cardinal_direction)
         * 
         * This is because it will already contain the *other* one
         * This also means multiple hashes will link to the same ID, this is fine
         */
        List<String> cardinalFix = new ArrayList<>();
        cardinalFix.add("minecraft:chest");
        cardinalFix.add("minecraft:trapped_chest");
        cardinalFix.add("minecraft:ender_chest");
        cardinalFix.add("minecraft:stonecutter_block");

        String[] facing_to_cardinal_directions = {
            "unused", "unused", "north", "south", "west", "east"
        };
        

        if (serverBlockDefinitionsNBT instanceof NbtMap serverNBTMap) {
            List<NbtMap> serverBlockDefinitions = serverNBTMap.getList("blocks", NbtType.COMPOUND);
            for (int i=0; i < serverBlockDefinitions.size(); i++) {
                // Server Block Definitions
                NbtMap serverBlockDefinition =  serverBlockDefinitions.get(i);
                this.serverBlockPaletteMap.put(BlockPaletteUtils.createHash(serverBlockDefinition), i);

                if (cardinalFix.contains(serverBlockDefinition.getString("name"))) { // Replace facing_direction with cardinal_direction
                    String cardinalDirection = serverBlockDefinition.getCompound("states").getString("minecraft:cardinal_direction");
                    int facing_direction=0;

                    switch (cardinalDirection) {
                        case "north":
                            facing_direction=2;
                            break;
                        case "south":
                            facing_direction=3;
                            break;
                        case "west":
                            facing_direction=4;
                            break;
                        case "east":
                            facing_direction=5;
                            break;
                    }

                    NbtMapBuilder fixedStatesBuilder = serverBlockDefinition.getCompound("states").toBuilder();
                    fixedStatesBuilder.remove("minecraft:cardinal_direction");
                    fixedStatesBuilder.putInt("facing_direction", facing_direction);
                    
                    NbtMapBuilder fixedServerBlockDefinitionBuilder = serverBlockDefinition.toBuilder();
                    fixedServerBlockDefinitionBuilder.remove("states");
                    fixedServerBlockDefinitionBuilder.putCompound("states", fixedStatesBuilder.build());

                    this.serverBlockPaletteMap.put(BlockPaletteUtils.createHash(fixedServerBlockDefinitionBuilder.build()), i); // Add this new fixed cardinal direction one
                }
            }

            this.serverBlockDefinitions = new UnknownBlockDefinitionRegistry();
        } else {
            log.error(
                    "Failed to load server block palette. Blocks will appear as runtime IDs in packet traces and creative_content.json!");
            throw new RuntimeException();
        }

        if (clientBlockDefinitionsNBT instanceof NbtMap clientNBTMap) {
            List<NbtMap> clientBlockDefinitions = clientNBTMap.getList("blocks", NbtType.COMPOUND);
            for (int i=0; i < clientBlockDefinitions.size(); i++) {
                // Server Block Definitions
                NbtMap clientBlockDefinition =  clientBlockDefinitions.get(i);
                this.clientBlockPaletteMap.put(BlockPaletteUtils.createHash(clientBlockDefinition), i);

                if (cardinalFix.contains(clientBlockDefinition.getString("name"))) { // Replace cardinal_direction with facing_direction
                    int facingDirection = clientBlockDefinition.getCompound("states").getInt("facing_direction");
                    String cardinal_direction = facing_to_cardinal_directions[facingDirection];

                    NbtMapBuilder fixedStatesBuilder = clientBlockDefinition.getCompound("states").toBuilder();
                    fixedStatesBuilder.remove("facing_direction");
                    fixedStatesBuilder.putString("minecraft:cardinal_direction", cardinal_direction);
                    
                    NbtMapBuilder fixedClientBlockDefinitionBuilder = clientBlockDefinition.toBuilder();
                    fixedClientBlockDefinitionBuilder.remove("states");
                    fixedClientBlockDefinitionBuilder.putCompound("states", fixedStatesBuilder.build());

                    this.clientBlockPaletteMap.put(BlockPaletteUtils.createHash(fixedClientBlockDefinitionBuilder.build()), i); // Add this new fixed cardinal direction one
                }
            }

            this.clientBlockDefinitions = new UnknownBlockDefinitionRegistry();
        } else {
            log.error(
                    "Failed to load client block palette. Blocks will appear as runtime IDs in packet traces and creative_content.json!");
            throw new RuntimeException();
        }

        // Compute the RID replacements
        for (Map.Entry<Integer, Integer> blockEntry : this.serverBlockPaletteMap.entrySet()) {
            this.RIDReplacementsServerToClient.put( blockEntry.getValue(), this.clientBlockPaletteMap.getOrDefault(blockEntry.getKey(), 0 ) );
        }
        for (Map.Entry<Integer, Integer> blockEntry : this.clientBlockPaletteMap.entrySet()) {
            this.RIDReplacementsClientToServer.put( blockEntry.getValue(), this.serverBlockPaletteMap.getOrDefault(blockEntry.getKey(), 0 ) );
        }
        

        log.info("Loading server...");
        ADVERTISEMENT.ipv4Port(this.proxyAddress.getPort())
                .ipv6Port(this.proxyAddress.getPort());
        this.server = new ServerBootstrap()
                .group(this.eventLoopGroup)
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_ADVERTISEMENT, ADVERTISEMENT.toByteBuf())
                .childHandler(new BedrockChannelInitializer<ProxyServerSession>() {

                    @Override
                    protected ProxyServerSession createSession0(BedrockPeer peer, int subClientId) {
                        return new ProxyServerSession(peer, subClientId, ProxyPass.this);
                    }

                    @Override
                    protected void initSession(ProxyServerSession session) {
                        session.setPacketHandler(new UpstreamPacketHandler(session, ProxyPass.this, account));
                    }
                })
                .bind(this.proxyAddress)
                .awaitUninterruptibly()
                .channel();
        log.info("Bedrock server started on {}", proxyAddress);

        loop();
    }

    public void newClient(InetSocketAddress socketAddress, Consumer<ProxyClientSession> sessionConsumer) {
        // TODO: Why can only one instance join a server?
        Channel channel = new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(this.eventLoopGroup)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, ProxyPass.CLIENT_CODEC.getRaknetProtocolVersion())
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .handler(new BedrockChannelInitializer<ProxyClientSession>() {

                    @Override
                    protected ProxyClientSession createSession0(BedrockPeer peer, int subClientId) {
                        log.debug("Creating new session");
                        return new ProxyClientSession(peer, subClientId, ProxyPass.this);
                    }

                    @Override
                    protected void initSession(ProxyClientSession session) {
                        log.debug("Session init!");
                        sessionConsumer.accept(session);
                    }
                })
                .connect(socketAddress)
                .syncUninterruptibly()
                .channel();

        this.clients.add(channel);
    }

    private void loop() {
        while (running.get()) {
            try {
                synchronized (this) {
                    this.wait();
                }
            } catch (InterruptedException e) {
                // ignore
            }

        }

        // Shutdown
        this.clients.forEach(Channel::disconnect);
        this.server.disconnect();
    }

    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            synchronized (this) {
                this.notify();
            }
        }
    }

    public void saveNBT(String dataName, Object dataTag) {
        Path path = dataDir.resolve(dataName + ".dat");
        try (OutputStream outputStream = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             NBTOutputStream nbtOutputStream = NbtUtils.createNetworkWriter(outputStream)) {
            nbtOutputStream.writeTag(dataTag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Object loadNBT(String dataName) {
        Path path = dataDir.resolve(dataName + ".dat");
        try (InputStream inputStream = Files.newInputStream(path);
            NBTInputStream nbtInputStream = NbtUtils.createNetworkReader(inputStream)) {
            return nbtInputStream.readTag();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Object loadGzipNBT(String dataName) {
        Path path = dataDir.resolve(dataName);
        try (InputStream inputStream = Files.newInputStream(path);
            NBTInputStream nbtInputStream = NbtUtils.createGZIPReader(inputStream)) {
            return nbtInputStream.readTag();
        } catch (IOException e) {
            return null;
        }
    }

    public void saveJson(String name, Object object) {
        Path outPath = dataDir.resolve(name);
        try (OutputStream outputStream = Files.newOutputStream(outPath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)) {
            ProxyPass.JSON_MAPPER.writer(PRETTY_PRINTER).writeValue(outputStream, object);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T loadJson(String name, TypeReference<T> reference) {
        Path path = dataDir.resolve(name);
        try (InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
            return ProxyPass.JSON_MAPPER.readValue(inputStream, reference);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveMojangson(String name, NbtMap nbt) {
        Path outPath = dataDir.resolve(name);
        try {
            Files.write(outPath, nbt.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isIgnoredPacket(Class<?> clazz) {
        return this.ignoredPackets.contains(clazz);
    }

    public boolean isFull() {
        return maxClients > 0 && this.clients.size() >= maxClients;
    }

    private Account getAuthenticatedAccount(boolean saveAuthDetails) throws Exception {
        Path authPath = Paths.get(".").resolve("auth.json");
        CloseableHttpClient client = MicrosoftConstants.createHttpClient();
        Account account;

        if (Files.notExists(authPath) || !Files.isRegularFile(authPath) || !saveAuthDetails) {
            StepMCChain.MCChain mcChain = MinecraftAuth.BEDROCK_DEVICE_CODE_LOGIN.getFromInput(client,
                    new StepMsaDeviceCode.MsaDeviceCodeCallback(msaDeviceCode -> {
                        log.info("Go to " + msaDeviceCode.verificationUri());
                        log.info("Enter code " + msaDeviceCode.userCode());

                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            try {
                                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                                clipboard.setContents(new StringSelection(msaDeviceCode.userCode()), null);
                                log.info("Copied code to clipboard");
                                Desktop.getDesktop().browse(new URI(msaDeviceCode.verificationUri()));
                            } catch (IOException | URISyntaxException e) {
                                log.error("Failed to open browser", e);
                            }
                        }
                    }));
            StepPlayFabToken.PlayFabToken playFabToken = MinecraftAuth.BEDROCK_PLAY_FAB_TOKEN.getFromInput(client, mcChain.prevResult().fullXblSession());
            account = new Account(mcChain, playFabToken);

            if (saveAuthDetails) {
                Files.write(authPath, account.toJson().toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            client.close();
            return account;
        }

        String accountString = new String(Files.readAllBytes(authPath), StandardCharsets.UTF_8);
        account = new Account(JsonParser.parseString(accountString).getAsJsonObject());
        account.refresh(client);
        Files.write(authPath, account.toJson().toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        client.close();
        return account;
    }
}
