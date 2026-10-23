package io.github.mariusbayizere.fraudshield.decision.testing;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The frozen Kafka contracts ({@code contracts/kafka}), loaded for tests: every envelope a test
 * renders is validated against the envelope schema, its topic's payload schema and its topic's
 * event-type binding, with format assertions on.
 */
public final class ContractSchemas {

  /** Repository root, relative to a backend module. */
  public static final Path REPOSITORY = Path.of("..", "..").toAbsolutePath().normalize();

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TOPIC =
      Pattern.compile(
          "- name: (\\S+)\\s+event_type: (\\S+)\\s+schema_version: (\\d+)[\\s\\S]*?"
              + "payload_schema: schemas/(\\S+)\\.schema\\.json");

  private final SchemaRegistry registry;
  private final Map<String, String[]> topics = new HashMap<>();

  /** Loads the schemas and the topic catalogue. */
  public ContractSchemas() {
    Path kafka = REPOSITORY.resolve("contracts/kafka");
    Map<String, String> schemas = new HashMap<>();
    try (Stream<Path> files = Files.list(kafka.resolve("schemas"))) {
      for (Path file : files.toList()) {
        String text = Files.readString(file);
        schemas.put(JSON.readTree(text).get("$id").asString(), text);
      }
      Matcher matcher = TOPIC.matcher(Files.readString(kafka.resolve("topics.yaml")));
      while (matcher.find()) {
        topics.put(
            matcher.group(1), new String[] {matcher.group(2), matcher.group(3), matcher.group(4)});
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (topics.size() != 13) {
      throw new IllegalStateException("expected 13 topics in topics.yaml, found " + topics.size());
    }
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(schemas).schemaRegistryConfig(config));
  }

  /**
   * Validation errors of one envelope on one topic; empty when it conforms.
   *
   * @param topic topic name
   * @param envelope the message value
   * @return error descriptions
   */
  public List<String> errors(String topic, byte[] envelope) {
    String[] binding = topics.get(topic);
    if (binding == null) {
      return List.of("unknown topic " + topic);
    }
    JsonNode node = JSON.readTree(envelope);
    List<String> errors = new java.util.ArrayList<>(describe(schema("envelope").validate(node)));
    if (!binding[0].equals(node.path("event_type").asString())) {
      errors.add("event_type " + node.path("event_type") + " is not the topic's " + binding[0]);
    }
    if (!binding[1].equals(node.path("schema_version").asString())) {
      errors.add("schema_version is not the topic's " + binding[1]);
    }
    errors.addAll(describe(schema(binding[2]).validate(node.get("payload"))));
    return errors;
  }

  private Schema schema(String name) {
    return registry.getSchema(SchemaLocation.of("urn:fraudshield:kafka:" + name));
  }

  private static List<String> describe(List<Error> errors) {
    return errors.stream().map(Error::toString).toList();
  }
}
