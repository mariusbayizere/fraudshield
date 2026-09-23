package io.github.mariusbayizere.fraudshield.decision.testing;

import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Generative property checks (ADR 0009's M6 plan, replacing jqwik). Each run draws a fresh seed
 * unless {@code -Dfs.property.seed=<seed>} fixes it; the seed is printed, and a failure reports the
 * seed, the try and the failing input so the case reproduces exactly. Failing inputs found this way
 * are added to the example-based tests as fixed regression cases.
 */
public final class Properties {

  /** System property that fixes the seed. */
  public static final String SEED_PROPERTY = "fs.property.seed";

  private Properties() {}

  /**
   * Checks a property over generated inputs.
   *
   * @param name property name, printed with the seed
   * @param tries number of inputs
   * @param generator draws one input from the random source
   * @param property throws {@link AssertionError} when the property fails
   * @param <T> input type
   */
  public static <T> void forAll(
      String name, int tries, Function<SplittableRandom, T> generator, Consumer<T> property) {
    String fixed = System.getProperty(SEED_PROPERTY);
    long seed = fixed != null ? Long.parseLong(fixed) : System.nanoTime() ^ name.hashCode();
    System.out.println("[property] " + name + " seed=" + seed + " tries=" + tries);
    SplittableRandom random = new SplittableRandom(seed);
    for (int i = 0; i < tries; i++) {
      T input = generator.apply(random);
      try {
        property.accept(input);
      } catch (AssertionError | RuntimeException e) {
        throw new AssertionError(
            "property '"
                + name
                + "' failed at try "
                + i
                + " with -D"
                + SEED_PROPERTY
                + "="
                + seed
                + " on input "
                + input,
            e);
      }
    }
  }
}
