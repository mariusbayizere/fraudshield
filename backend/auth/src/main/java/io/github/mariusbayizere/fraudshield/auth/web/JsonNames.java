package io.github.mariusbayizere.fraudshield.auth.web;

/** Java property names to the contract's snake_case JSON names. */
public final class JsonNames {

  private JsonNames() {}

  /**
   * Converts camelCase to snake_case.
   *
   * @param name Java name
   * @return JSON name
   */
  public static String snake(String name) {
    StringBuilder out = new StringBuilder(name.length() + 4);
    for (char c : name.toCharArray()) {
      if (Character.isUpperCase(c)) {
        out.append('_').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
