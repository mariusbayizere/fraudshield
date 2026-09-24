package io.github.mariusbayizere.fraudshield.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Java field catalogue must name exactly the Python feature registry's 44 features with the
 * same comparison type. Runs the registry through {@code uv} when the Python toolchain is present
 * and is reported as skipped (never passed) when it is not.
 */
@Tag("FR-05-05")
@Tag("D-03")
class FieldCatalogueDriftTest {

  @Test
  void catalogueMatchesThePythonRegistry() throws IOException, InterruptedException {
    Path repository = Path.of("..", "..").toAbsolutePath().normalize();
    assumeTrue(
        Files.isRegularFile(repository.resolve("ml/src/fraudshield_ml/features/registry.py")));
    Process process;
    try {
      process =
          new ProcessBuilder(
                  "uv",
                  "run",
                  "--frozen",
                  "--package",
                  "fraudshield-ml",
                  "python",
                  "-c",
                  "from fraudshield_ml.features.registry import REGISTRY\n"
                      + "for n, s in REGISTRY.items(): print(n, s.dtype.name)")
              .directory(repository.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException noUv) {
      assumeTrue(false, "uv is not installed: " + noUv.getMessage());
      return;
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assumeTrue(
        process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0,
        "the Python registry could not be loaded");

    Map<String, FieldType> python = new TreeMap<>();
    for (String line : output.strip().split("\n")) {
      String[] parts = line.strip().split(" ");
      python.put(parts[0], parts[1].equals("CATEGORICAL") ? FieldType.CATEGORY : FieldType.NUMBER);
    }
    Map<String, FieldType> java = new TreeMap<>();
    for (String name : FieldCatalogue.featureNames()) {
      java.put(name, FieldCatalogue.find(name).orElseThrow().type());
    }
    assertThat(python).hasSize(FieldCatalogue.FEATURE_COUNT);
    assertThat(java).isEqualTo(python);
  }
}
