package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Each country pack's currency and its fixed simulation rate in RWF ({@code fx_rate_to_base}), read
 * from the packs themselves ({@code dataset/generator/params/countries}, packaged into this
 * module), so the demo database converts amounts at the rates the training data was generated at.
 */
final class CountryPackRates {

  private static final String LOCATION = "classpath*:fraudshield/countries/*.yaml";
  private static final String BASE = "RWF";

  private CountryPackRates() {}

  /**
   * Reads every packaged pack.
   *
   * @return RWF per unit of each pack's currency, by currency code, the base currency included
   * @throws IllegalStateException if no pack is packaged, two packs share a currency, a rate is not
   *     positive, or the base currency's rate is not exactly one
   */
  static Map<String, BigDecimal> load() {
    Map<String, BigDecimal> rates = new TreeMap<>();
    try {
      for (Resource pack : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
        Map<String, Object> parameters = parameters(pack);
        String currency = String.valueOf(value(parameters, "currency"));
        // Through the decimal text, not the double: 9.7768 stays 9.7768 in numeric(20, 8).
        BigDecimal rate = new BigDecimal(String.valueOf(value(parameters, "fx_rate_to_base")));
        if (rate.signum() <= 0) {
          throw new IllegalStateException(
              pack.getFilename() + ": fx_rate_to_base must be positive");
        }
        if (rates.putIfAbsent(currency, rate) != null) {
          throw new IllegalStateException(currency + " is declared by two country packs");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the country packs", e);
    }
    if (rates.isEmpty()) {
      throw new IllegalStateException("no country pack is packaged under " + LOCATION);
    }
    BigDecimal base = rates.get(BASE);
    if (base == null || base.compareTo(BigDecimal.ONE) != 0) {
      throw new IllegalStateException("the " + BASE + " pack must declare fx_rate_to_base 1");
    }
    return Map.copyOf(rates);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parameters(Resource pack) throws IOException {
    try (InputStream in = pack.getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      return (Map<String, Object>) Objects.requireNonNull(root.get("parameters"), "parameters");
    }
  }

  @SuppressWarnings("unchecked")
  private static Object value(Map<String, Object> parameters, String name) {
    Map<String, Object> entry =
        (Map<String, Object>) Objects.requireNonNull(parameters.get(name), name);
    return Objects.requireNonNull(entry.get("value"), name + ".value");
  }
}
