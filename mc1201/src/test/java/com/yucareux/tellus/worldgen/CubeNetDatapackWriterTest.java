package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CubeNetDatapackWriterTest {
   @TempDir Path temp;

   @Test
   void writesCodecBackedSettingsAndDimensionDocuments() throws Exception {
      Path output = temp.resolve("tellus-cube-net-world");
      CubeNetDatapackWriter.Result result = CubeNetDatapackWriter.write(output, "demo:world", 1000.0, 64);

      assertEquals(3, result.files().size());
      assertTrue(Files.isRegularFile(output.resolve("pack.mcmeta")));
      assertTrue(Files.isRegularFile(output.resolve("data/demo/dimension_type/world_surface.json")));
      Path dimensionPath = output.resolve("data/demo/dimension/world_cube_net.json");
      assertTrue(Files.isRegularFile(dimensionPath));
      JsonObject dimension = JsonParser.parseString(Files.readString(dimensionPath, StandardCharsets.UTF_8)).getAsJsonObject();
      JsonObject settings = dimension.getAsJsonObject("generator")
         .getAsJsonObject("biome_source").getAsJsonObject("settings");
      assertTrue(settings.has("lake_freezing"), settings.toString());
      assertTrue(settings.has("lava_pools"));
      assertTrue(settings.has("geodes"));
      assertTrue(settings.has("enable_water"));
      assertTrue(settings.has("teleport_progress"));
      assertTrue(settings.has("height_offset"));
      assertTrue(settings.has("spawn_latitude"));
      assertTrue(settings.has("spawn_longitude"));
      assertTrue(settings.has("terrestrial_height_scale"));
      assertTrue(settings.has("oceanic_height_scale"));
      assertEquals(1000.0, dimension.getAsJsonObject("generator").get("world_scale").getAsDouble());
   }

   @Test
   void refusesToWriteOverNonEmptyDirectory() throws Exception {
      Path output = temp.resolve("existing");
      Files.createDirectories(output);
      Files.writeString(output.resolve("keep.txt"), "keep", StandardCharsets.UTF_8);

      assertThrows(Exception.class, () -> CubeNetDatapackWriter.write(output, "demo:world", 1000.0, 64));
      assertEquals("keep", Files.readString(output.resolve("keep.txt"), StandardCharsets.UTF_8));
   }
}
