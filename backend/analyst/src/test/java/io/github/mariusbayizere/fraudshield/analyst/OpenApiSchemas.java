package io.github.mariusbayizere.fraudshield.analyst;

import com.networknt.schema.Error;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The packaged OpenAPI contract's component schemas, as JSON Schema 2020-12 with format assertions
 * on: every response these tests receive is validated against the schema the contract gives it.
 */
final class OpenApiSchemas {

  private static final String ID = "urn:fraudshield:openapi";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SchemaRegistry REGISTRY = load();

  private OpenApiSchemas() {}

  /**
   * Validation errors of a body against one component schema; empty when it conforms.
   *
   * @param schema the component's name, for example {@code AlertPage}
   * @param body the response body
   * @return error descriptions
   */
  static List<String> errors(String schema, JsonNode body) {
    return REGISTRY
        .getSchema(SchemaLocation.of(ID + "#/components/schemas/" + schema))
        .validate(body)
        .stream()
        .map(Error::toString)
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static SchemaRegistry load() {
    try (InputStream in =
        Objects.requireNonNull(
            OpenApiSchemas.class
                .getClassLoader()
                .getResourceAsStream("fraudshield/contracts/fraudshield-api.yaml"),
            "the packaged contract")) {
      Map<String, Object> document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      document.put("$id", ID);
      String text = JSON.writeValueAsString(document);
      SchemaRegistryConfig config =
          SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
      return SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder -> builder.schemas(Map.of(ID, text)).schemaRegistryConfig(config));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
