package io.github.mariusbayizere.fraudshield.persistence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Applies Flyway migrations and, with the {@code dev} or {@code demo} profile and demo seeding
 * enabled, seeds synthetic demo data (ADR 0017, ADR 0019). Used by {@code make migrate} and {@code
 * make seed-demo}; the API service will embed the same migrations.
 */
@SpringBootApplication
public class DatabaseToolApplication {

  /**
   * Starts the tool.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    // A one-shot tool: exit with the context's exit code once migrations and seeding are done.
    System.exit(SpringApplication.exit(SpringApplication.run(DatabaseToolApplication.class, args)));
  }
}
