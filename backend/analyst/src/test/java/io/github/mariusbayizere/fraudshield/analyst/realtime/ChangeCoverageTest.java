package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every writer that changes an entry's status, version or assignment publishes (ADR 0115 point
 * 6.2). The scan lists every UPDATE and INSERT of {@code alert_queue_entries} in main code; each is
 * either known here, with the test that asserts its event, or the build fails.
 */
class ChangeCoverageTest {

  private static final Path BACKEND = Path.of("..").toAbsolutePath().normalize();

  private static final Pattern WRITE =
      Pattern.compile(
          "(UPDATE alert_queue_entries SET [^\"]*?(?:WHERE|$)|INSERT INTO alert_queue_entries)");

  /**
   * The known writers, as "file: statement start", with the test asserting each event. A writer
   * that changes neither status, version nor assignment is listed with the reason it publishes
   * nothing.
   */
  private static final Map<String, String> KNOWN =
      Map.ofEntries(
          Map.entry(
              "AlertDecisions.java: UPDATE alert_queue_entries SET status = ?, version",
              "DECIDED and UNDONE: AnalystDecisionsTest decidingHold..., undoInsideTheWindow..."),
          Map.entry(
              "AlertDecisions.java: UPDATE alert_queue_entries SET status = 'ESCALATED', version",
              "ESCALATED: AnalystDecisionsTest escalatingStopsTheTimer..."),
          Map.entry(
              "AlertDecisions.java: UPDATE alert_queue_entries SET status = 'OVERRIDDEN', version",
              "OVERRIDDEN: AnalystDecisionsTest riskOfficerFraudOverride..."),
          Map.entry(
              "AlertDecisions.java: INSERT INTO alert_queue_entries",
              "ESCALATION_CREATED: AnalystDecisionsTest escalatingStopsTheTimer..."),
          Map.entry(
              "DecisionOwner.java: UPDATE alert_queue_entries SET status = ?, version",
              "TIMED_OUT: DecisionOwnerTest theTimeoutPublishes...; the sink's late close:"
                  + " DecisionOwnerTest theOwnersOwnFirstDecisionArrivingAfterItsTimeout..."),
          Map.entry(
              "DecisionOwner.java: UPDATE alert_queue_entries SET status = 'OVERRIDDEN',"
                  + " resolution_id = ?, version",
              "RESOLVED: AnalystDecisionsTest operatorResolutionIsApplied...; the sink's late"
                  + " close after a RESOLVE returns the same LateClose"),
          Map.entry(
              "DecisionOwner.java: UPDATE alert_queue_entries SET resolution_id = ?",
              "none: marks the claimed entry resolved; status and version are unchanged"),
          Map.entry(
              "PostgresSink.java: INSERT INTO alert_queue_entries",
              "excepted: a raised entry's event is its fs.alerts.* message (point 6.2)"));

  @Test
  @Tag("FR-04-02")
  void everyWriterOfAnEntryIsKnownToPublish() throws IOException {
    Map<String, Integer> found = new TreeMap<>();
    try (Stream<Path> files = Files.walk(BACKEND)) {
      for (Path file :
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> p.toString().contains("/src/main/java/"))
              .toList()) {
        String text = Files.readString(file).replaceAll("\"\\s*\\+\\s*\"", "");
        Matcher m = WRITE.matcher(text.replaceAll("\\s+", " "));
        while (m.find()) {
          String statement = m.group(1);
          String key =
              file.getFileName() + ": " + statement.replaceAll(" (?:\\+ 1 )?(?:WHERE)?$", "");
          found.merge(prefixOf(key), 1, Integer::sum);
        }
      }
    }
    assertThat(found.keySet())
        .as("a new writer of alert_queue_entries must publish and be listed here")
        .isSubsetOf(KNOWN.keySet());
    assertThat(found.keySet()).as("the scan still finds every known writer").isEqualTo(KNOWN.keySet());
  }

  /** The longest known key the statement starts with, or the statement itself. */
  private static String prefixOf(String statement) {
    String best = statement;
    int length = 0;
    for (String known : KNOWN.keySet()) {
      if (statement.startsWith(known) && known.length() > length) {
        best = known;
        length = known.length();
      }
    }
    return best;
  }
}
