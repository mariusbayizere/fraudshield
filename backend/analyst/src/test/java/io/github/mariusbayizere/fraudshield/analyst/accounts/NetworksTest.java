package io.github.mariusbayizere.fraudshield.analyst.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The network's selection and edges, as the contract's {@code AccountNetwork} describes them. */
@Tag("FR-04-08")
class NetworksTest {

  private static Networks.Link link(String source, String target) {
    return new Networks.Link(source, target);
  }

  @Test
  void counterpartiesAreRankedByLinksInEitherDirectionThenByToken() {
    List<Networks.Link> links =
        List.of(link("A", "C"), link("B", "A"), link("A", "B"), link("D", "A"), link("A", "A"));

    Map<String, Integer> degrees = Networks.firstDegree("A", links);

    assertThat(degrees.keySet()).containsExactly("A", "B", "C", "D");
    assertThat(degrees.values()).containsExactly(0, 1, 1, 1);
  }

  @Test
  void aSecondDegreeNodeCountsItsStrongestLinkToAnyOneNeighbour() {
    Map<String, Integer> degrees =
        Networks.firstDegree("A", List.of(link("A", "B"), link("A", "C")));
    List<Networks.Link> second =
        List.of(
            link("B", "X"),
            link("C", "X"),
            link("B", "Y"),
            link("Y", "B"),
            link("B", "A"),
            link("C", "B"));

    Networks.secondDegree(degrees, second);

    assertThat(degrees.keySet())
        .as("Y has two links to B; X one to each of B and C, so its count is one")
        .containsExactly("A", "B", "C", "Y", "X");
    assertThat(degrees.get("Y")).isEqualTo(2);
  }

  @Test
  void theNetworkStopsAtFiftyNodes() {
    List<Networks.Link> links = new ArrayList<>();
    for (int i = 0; i < 70; i++) {
      links.add(link("A", String.format("N%03d", i)));
    }
    Map<String, Integer> degrees = Networks.firstDegree("A", links);
    Networks.secondDegree(degrees, List.of(link("N000", "Z")));

    assertThat(degrees).hasSize(Networks.MAX_NODES).doesNotContainKey("Z");
  }

  @Test
  void edgesAreOrderedPairsBetweenChosenNodesCountedOnce() {
    List<Map<String, Object>> edges =
        Networks.edges(
            java.util.Set.of("A", "B"),
            List.of(link("A", "B"), link("A", "B"), link("B", "A"), link("A", "Z")));

    assertThat(edges).hasSize(2);
    assertThat(edges.getFirst())
        .containsEntry("source", "A")
        .containsEntry("target", "B")
        .containsEntry("transaction_count", 2);
  }
}
