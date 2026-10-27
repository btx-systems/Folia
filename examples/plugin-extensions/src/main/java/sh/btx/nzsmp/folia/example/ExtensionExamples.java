package sh.btx.nzsmp.folia.example;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import sh.btx.nzsmp.folia.generation.*;

/** Independent of transport/storage: complete one uniform floor section. */
public final class ExtensionExamples extends JavaPlugin {
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new AsyncChunkGenerator() {
            @Override
            public java.util.concurrent.CompletionStage<GeneratedChunk> generate(GenerationRequest request) {
                if ("encoded".equals(id)) return CompletableFuture.completedFuture(encodedFloor(request));
                if ("serialized".equals(id)) return CompletableFuture.completedFuture(serializedFloor(request));
                return CompletableFuture.completedFuture(new SectionChunkData(List.of(new ChunkSectionData(
                    request.world().minHeight() >> 4, PackedPalette.uniform(Material.STONE.createBlockData()),
                    PackedPalette.uniform(Key.key("minecraft:plains")))), BiomePolicy.PROVIDED, ChunkExtras.EMPTY));
            }
            @Override public Location getFixedSpawnLocation(World world, Random random) {
                return new Location(world, 0.5, world.getMinHeight() + 17, 0.5);
            }
        };
    }

    private static EncodedSectionChunkData encodedFloor(GenerationRequest request) {
        // This reference codec deliberately supports one advertised format. A transport
        // adapter can use the same descriptor and numeric mappings without Bukkit setters.
        if (!request.registries().sectionEncoding().format().equals("minecraft:network-section/26.3")) throw new IllegalArgumentException("Unsupported section encoding");
        int stone = request.registries().blockStates().indexOf("minecraft:stone");
        int plains = request.registries().biomes().indexOf(Key.key("minecraft:plains"));
        if (stone < 0 || plains < 0) throw new IllegalArgumentException("Required registry entries missing");
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var output = new java.io.DataOutputStream(bytes);
            output.writeShort(4096); // non-air count
            output.writeShort(0); // fluid count
            output.writeByte(0); writeVarInt(output, stone); // uniform block container
            output.writeByte(0); writeVarInt(output, plains); // uniform biome container
            var data = sh.btx.nzsmp.folia.data.BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(bytes.toByteArray()));
            return new EncodedSectionChunkData(request.registries().sectionEncoding(), List.of(new EncodedSection(request.world().minHeight() >> 4, data)), BiomePolicy.PROVIDED, ChunkExtras.EMPTY);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void writeVarInt(java.io.DataOutputStream output, int value) throws java.io.IOException {
        while ((value & ~0x7F) != 0) { output.writeByte((value & 0x7F) | 0x80); value >>>= 7; }
        output.writeByte(value);
    }

    private static SerializedChunkData serializedFloor(GenerationRequest request) {
        // Minimal standard chunk NBT, written without Minecraft internals or a service.
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var out = new java.io.DataOutputStream(bytes);
            out.writeByte(10); out.writeUTF(""); // root compound
            nbtInt(out, "DataVersion", request.registries().sectionEncoding().dataVersion());
            nbtInt(out, "xPos", request.chunkX()); nbtInt(out, "zPos", request.chunkZ());
            nbtInt(out, "yPos", request.world().minHeight() >> 4);
            nbtString(out, "Status", "minecraft:features");
            out.writeByte(9); out.writeUTF("sections"); out.writeByte(10); out.writeInt(1);
            out.writeByte(1); out.writeUTF("Y"); out.writeByte(request.world().minHeight() >> 4);
            out.writeByte(10); out.writeUTF("block_states");
            out.writeByte(9); out.writeUTF("palette"); out.writeByte(8); out.writeInt(1);
            out.writeUTF("minecraft:stone"); out.writeByte(0);
            out.writeByte(10); out.writeUTF("biomes");
            out.writeByte(9); out.writeUTF("palette"); out.writeByte(8); out.writeInt(1); out.writeUTF("minecraft:plains");
            out.writeByte(0); // biome container
            out.writeByte(0); // section
            out.writeByte(0); // root
            return new SerializedChunkData(request.registries().sectionEncoding().dataVersion(), sh.btx.nzsmp.folia.data.BinaryData.takeOwnership(java.nio.ByteBuffer.wrap(bytes.toByteArray())));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void nbtInt(java.io.DataOutputStream out, String name, int value) throws java.io.IOException {
        out.writeByte(3); out.writeUTF(name); out.writeInt(value);
    }
    private static void nbtString(java.io.DataOutputStream out, String name, String value) throws java.io.IOException {
        out.writeByte(8); out.writeUTF(name); out.writeUTF(value);
    }

    @Override
    public void onEnable() {
        String phase = System.getProperty("folia.extensions.smoke", "");
        if (phase.isEmpty()) return;
        if (phase.startsWith("player-")) {
            this.getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                    try {
                        var player = event.getPlayer();
                        var key = new org.bukkit.NamespacedKey(ExtensionExamples.this, "player-roundtrip");
                        if (phase.equals("player-write")) {
                            player.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND, 7));
                            player.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, "saved");
                            player.setStatistic(org.bukkit.Statistic.JUMP, 42);
                            var root = getServer().getAdvancement(org.bukkit.NamespacedKey.minecraft("story/root"));
                            if (root == null) throw new AssertionError("Root advancement missing");
                            var progress = player.getAdvancementProgress(root);
                            for (String criterion : progress.getRemainingCriteria()) progress.awardCriteria(criterion);
                        } else {
                            var item = player.getInventory().getItem(0);
                            if (item == null || item.getType() != Material.DIAMOND || item.getAmount() != 7) throw new AssertionError("Player inventory missing");
                            if (!"saved".equals(player.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING))) throw new AssertionError("Player PDC missing");
                            if (player.getStatistic(org.bukkit.Statistic.JUMP) != 42) throw new AssertionError("Player statistics missing");
                            var root = getServer().getAdvancement(org.bukkit.NamespacedKey.minecraft("story/root"));
                            if (root == null || !player.getAdvancementProgress(root).isDone()) throw new AssertionError("Advancement progress missing");
                            if (!getServer().getOfflinePlayer(player.getUniqueId()).hasPlayedBefore()) throw new AssertionError("Offline-player data missing");
                        }
                        getLogger().info("EXTENSION_SMOKE_PASS " + phase);
                    } catch (Throwable error) {
                        getLogger().log(java.util.logging.Level.SEVERE, "EXTENSION_SMOKE_FAIL", error);
                    }
                }
                @org.bukkit.event.EventHandler
                public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                    getServer().getGlobalRegionScheduler().runDelayed(ExtensionExamples.this, task -> getServer().shutdown(), 20);
                }
                @org.bukkit.event.EventHandler
                public void onReady(org.bukkit.event.server.ServerLoadEvent event) {
                    getServer().getGlobalRegionScheduler().runDelayed(ExtensionExamples.this, task -> getServer().shutdown(), 1200);
                }
            }, this);
            return;
        }
        this.getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onReady(org.bukkit.event.server.ServerLoadEvent event) {
                runSmoke(phase);
            }
        }, this);
    }

    private void runSmoke(String phase) {
        World world = this.getServer().getWorlds().getFirst();
        world.getChunkAtAsync(0, 0).thenAccept(chunk -> this.getServer().getRegionScheduler().execute(this, world, 0, 0, () -> {
            try {
                int y = world.getMinHeight() + 16;
                if (phase.equals("vanilla")) {
                    if (world.getGenerator() != null) throw new AssertionError("Unexpected custom generator");
                    this.getLogger().info("EXTENSION_SMOKE_PASS vanilla");
                    return;
                }
                if (!(world.getGenerator() instanceof AsyncChunkGenerator)) throw new AssertionError("Async generator was not selected");
                if (world.getBlockAt(0, world.getMinHeight(), 0).getType() != Material.STONE
                    || !world.getBlockAt(0, world.getMinHeight() + 32, 0).isEmpty()) throw new AssertionError("Generated floor does not match provider terrain");
                var key = new org.bukkit.NamespacedKey(this, "roundtrip");
                if (phase.equals("write")) {
                    world.getBlockAt(1, y, 1).setType(Material.CHEST);
                    var chest = (org.bukkit.block.Chest) world.getBlockAt(1, y, 1).getState();
                    chest.getBlockInventory().setItem(0, new org.bukkit.inventory.ItemStack(Material.DIAMOND, 7));
                    chunk.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, "saved");
                    world.getBlockAt(3, y, 3).setType(Material.RED_BED);
                    var cow = world.spawn(new Location(world, 6.5, y + 1, 6.5), org.bukkit.entity.Cow.class);
                    cow.setAI(false);
                    java.nio.file.Files.writeString(this.getDataFolder().toPath().resolve("smoke-world-id"), world.getUID().toString());
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("world", "generated"));
                    java.nio.file.Files.writeString(java.nio.file.Path.of("world", "generated", "fixture.bin"), "asset survives cache deletion");
                } else {
                    if (world.getBlockAt(1, y, 1).getType() != Material.CHEST) throw new AssertionError("Chest missing");
                    var chest = (org.bukkit.block.Chest) world.getBlockAt(1, y, 1).getState();
                    var item = chest.getBlockInventory().getItem(0);
                    if (item == null || item.getType() != Material.DIAMOND || item.getAmount() != 7) throw new AssertionError("Inventory missing");
                    if (!"saved".equals(chunk.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING))) throw new AssertionError("Chunk PDC missing");
                    if (!world.getUID().toString().equals(java.nio.file.Files.readString(this.getDataFolder().toPath().resolve("smoke-world-id")))) throw new AssertionError("World UUID changed");
                    if (!"asset survives cache deletion".equals(java.nio.file.Files.readString(java.nio.file.Path.of("world", "generated", "fixture.bin")))) throw new AssertionError("World asset missing");
                    if (java.util.Arrays.stream(chunk.getEntities()).noneMatch(entity -> entity instanceof org.bukkit.entity.Cow)) throw new AssertionError("Entity missing");
                }
                this.getLogger().info("EXTENSION_SMOKE_PASS " + phase);
            } catch (Throwable error) { this.getLogger().log(java.util.logging.Level.SEVERE, "EXTENSION_SMOKE_FAIL", error); }
            finally { this.getServer().shutdown(); }
        })).exceptionally(error -> { this.getLogger().log(java.util.logging.Level.SEVERE, "EXTENSION_SMOKE_FAIL", error); this.getServer().shutdown(); return null; });
    }
}
