package io.github.mariusbayizere.fraudshield.analyst.accounts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The selection rules of {@code getAccountNetwork} (FR-04-08, ADR 0110 point 7), apart from data
 * access: who is a node, in which order, and which pairs are edges.
 */
final class Networks {

  /** The contract's {@code maxItems} for nodes. */
  static final int MAX_NODES = 50;

  private Networks() {}

  /**
   * One transaction between two accounts, as the network reads it.
   *
   * @param source the sending account's token
   * @param target the receiving account's token
   */
  record Link(String source, String target) {}

  /**
   * The account and its counterparties, in the contract's order: the account, then its
   * counterparties by the number of transactions linking each to it, ties by token. At most {@link
   * #MAX_NODES}.
   *
   * @param account the account
   * @param links every transaction of the window with the account at either end
   * @return token to degree, in order
   */
  static Map<String, Integer> firstDegree(String account, List<Link> links) {
    Map<String, Integer> degrees = new LinkedHashMap<>();
    degrees.put(account, 0);
    Map<String, Integer> counts = new HashMap<>();
    for (Link link : links) {
      String other = link.source().equals(account) ? link.target() : link.source();
      if (!other.equals(account)) {
        counts.merge(other, 1, Integer::sum);
      }
    }
    for (String token : ranked(counts)) {
      if (degrees.size() == MAX_NODES) {
        break;
      }
      degrees.put(token, 1);
    }
    return degrees;
  }

  /**
   * Adds the second-degree nodes while room remains: their counterparties not already chosen, by
   * the most transactions linking each to any one degree-1 node, ties by token.
   *
   * @param degrees the account and its degree-1 nodes, extended in place
   * @param links every transaction of the window with a degree-1 node at either end
   */
  static void secondDegree(Map<String, Integer> degrees, List<Link> links) {
    Map<String, Map<String, Integer>> viaNeighbour = new HashMap<>();
    for (Link link : links) {
      addSecond(degrees, viaNeighbour, link.source(), link.target());
      addSecond(degrees, viaNeighbour, link.target(), link.source());
    }
    Map<String, Integer> second = new HashMap<>();
    for (Map.Entry<String, Map<String, Integer>> entry : viaNeighbour.entrySet()) {
      int most = 0;
      for (int count : entry.getValue().values()) {
        most = Math.max(most, count);
      }
      second.put(entry.getKey(), most);
    }
    for (String token : ranked(second)) {
      if (degrees.size() == MAX_NODES) {
        break;
      }
      degrees.put(token, 2);
    }
  }

  /** Counts a link from a degree-1 node to a node not yet chosen, per degree-1 node. */
  private static void addSecond(
      Map<String, Integer> degrees,
      Map<String, Map<String, Integer>> viaNeighbour,
      String neighbour,
      String other) {
    Integer degree = degrees.get(neighbour);
    if (degree == null || degree != 1 || degrees.containsKey(other)) {
      return;
    }
    viaNeighbour.computeIfAbsent(other, k -> new HashMap<>()).merge(neighbour, 1, Integer::sum);
  }

  private static List<String> ranked(Map<String, Integer> counts) {
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
    entries.sort(
        Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
            .thenComparing(Map.Entry.comparingByKey()));
    List<String> tokens = new ArrayList<>(entries.size());
    for (Map.Entry<String, Integer> entry : entries) {
      tokens.add(entry.getKey());
    }
    return tokens;
  }

  /**
   * The edges: every ordered pair of chosen nodes with at least one transaction from source to
   * target, each once, ordered by source then target.
   *
   * @param nodes the chosen nodes
   * @param links every transaction of the window between two chosen nodes
   * @return the contract's edge items
   */
  static List<Map<String, Object>> edges(Set<String> nodes, List<Link> links) {
    Map<Link, Integer> counts = new HashMap<>();
    for (Link link : links) {
      if (nodes.contains(link.source()) && nodes.contains(link.target())) {
        counts.merge(link, 1, Integer::sum);
      }
    }
    List<Link> ordered = new ArrayList<>(counts.keySet());
    ordered.sort(Comparator.comparing(Link::source).thenComparing(Link::target));
    List<Map<String, Object>> edges = new ArrayList<>(ordered.size());
    for (Link link : ordered) {
      Map<String, Object> edge = new LinkedHashMap<>();
      edge.put("source", link.source());
      edge.put("target", link.target());
      edge.put("transaction_count", counts.get(link));
      edges.add(edge);
    }
    return edges;
  }

  /**
   * The contract's node items.
   *
   * @param degrees token to degree, in order
   * @param confirmedFraud the tokens that sent a transaction whose current label is FRAUD
   * @return the node items
   */
  static List<Map<String, Object>> nodeItems(
      Map<String, Integer> degrees, Set<String> confirmedFraud) {
    List<Map<String, Object>> items = new ArrayList<>(degrees.size());
    for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("token", entry.getKey());
      node.put("degree", entry.getValue());
      node.put("confirmed_fraud", confirmedFraud.contains(entry.getKey()));
      items.add(node);
    }
    return items;
  }

  /**
   * The degree-1 tokens of a node map, for the second query.
   *
   * @param degrees token to degree
   * @return the degree-1 tokens
   */
  static Set<String> degreeOne(Map<String, Integer> degrees) {
    Set<String> tokens = new LinkedHashSet<>();
    for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
      if (entry.getValue() == 1) {
        tokens.add(entry.getKey());
      }
    }
    return tokens;
  }
}
