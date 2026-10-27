package sh.btx.nzsmp.folia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.btx.nzsmp.folia.storage.PluginPersistence;
import static org.junit.jupiter.api.Assertions.*;

class StorageConfigurationTest {
    @TempDir Path directory;

    @Test
    void malformedConfigurationCannotSilentlySelectVanilla() throws Exception {
        Path file = directory.resolve("storage.yml");
        Files.writeString(file, "world-provider: [unterminated\n");
        assertThrows(IOException.class, () -> PluginPersistence.loadConfiguration(file));
        Files.writeString(file, "world-provider: example:records\n");
        assertEquals("example:records", PluginPersistence.loadConfiguration(file).getString("world-provider"));
        assertFalse(PluginPersistence.loadConfiguration(directory.resolve("absent.yml")).contains("world-provider"));
    }
}
