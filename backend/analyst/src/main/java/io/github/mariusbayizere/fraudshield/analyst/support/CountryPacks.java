package io.github.mariusbayizere.fraudshield.analyst.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Each country pack's currency and UTC offset, read from the packs themselves ({@code
 * dataset/generator/params/countries}, packaged into this module), so that local time here is the
 * scorer's: the country is derived from the transaction's currency ({@code serving/features.py},
 * ADR 0110 point 5).
 */
public final class CountryPacks {

  private static final String LOCATION = "classpath*:fraudshield/countries/*.yaml";

  private final Map<String, String> countryOfCurrency;
  private final Map<String, ZoneOffset> offsetOfCountry;

  private CountryPacks(Map<String, String> countryOfCurrency, Map<String, ZoneOffset> offsets) {
    this.countryOfCurrency = Map.copyOf(countryOfCurrency);
    this.offsetOfCountry = Map.copyOf(offsets);
  }

  /**
   * Reads every packaged pack.
   *
   * @return the packs
   * @throws IllegalStateException if none is packaged, or two packs share a currency
   */
  public static CountryPacks load() {
    Map<String, String> countries = new HashMap<>();
    Map<String, ZoneOffset> offsets = new HashMap<>();
    try {
      Resource[] packs = new PathMatchingResourcePatternResolver().getResources(LOCATION);
      for (Resource pack : packs) {
        String file = Objects.requireNonNull(pack.getFilename(), "pack name");
        String country = file.substring(0, file.indexOf('.'));
        Map<String, Object> parameters = parameters(pack);
        String currency = String.valueOf(value(parameters, "currency"));
        int hours = ((Number) value(parameters, "utc_offset_hours")).intValue();
        if (countries.putIfAbsent(currency, country) != null) {
          throw new IllegalStateException(
              currency + " is declared by two packs, so a transaction's country is unknown");
        }
        offsets.put(country, ZoneOffset.ofHours(hours));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the country packs", e);
    }
    if (countries.isEmpty()) {
      throw new IllegalStateException("no country pack is packaged under " + LOCATION);
    }
    return new CountryPacks(countries, offsets);
  }

  /**
   * The hour of day at which a transaction happened, in local time as the scorer computes it: the
   * offset of the country its currency belongs to. A currency no pack declares, which the scorer
   * cannot score either, falls back to the institution's country.
   *
   * @param at the transaction's time
   * @param currency its currency
   * @param institutionCountry the institution's country, the fallback
   * @return the local hour, 0 to 23
   */
  public int localHour(Instant at, String currency, String institutionCountry) {
    String country = countryOfCurrency.getOrDefault(currency, institutionCountry);
    ZoneOffset offset = offsetOfCountry.getOrDefault(country, ZoneOffset.UTC);
    return at.atOffset(offset).getHour();
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
