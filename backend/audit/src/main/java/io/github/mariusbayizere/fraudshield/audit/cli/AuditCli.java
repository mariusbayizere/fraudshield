package io.github.mariusbayizere.fraudshield.audit.cli;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Process entry point of {@code fraudshield audit verify}; see {@link AuditVerifyCommand}. */
public final class AuditCli {

  private AuditCli() {}

  /**
   * Runs the command and exits with its code.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    System.exit(new AuditVerifyCommand(System.getenv(), out::println, err::println).run(args));
  }
}
