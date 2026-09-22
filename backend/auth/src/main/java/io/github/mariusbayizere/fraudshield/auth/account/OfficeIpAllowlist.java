package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An institution's office egress ranges, which get a higher sign-in ceiling (D-26), and their
 * administration. Lookups are cached per institution for a short time; a change evicts the local
 * cache at once and other instances within the cache time to live.
 */
public final class OfficeIpAllowlist {

  private static final Pattern IP_LITERAL =
      Pattern.compile("^([0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]{1,44})$");

  private final JdbcTemplate jdbc;
  private final TenantTransactions tenants;
  private final long cacheTtlNanos;
  private final LongSupplier nanoTime;
  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

  private record Cached(List<Range> ranges, long expiresAtNanos) {}

  /**
   * One stored range.
   *
   * @param id entry ID
   * @param cidr network in CIDR notation, as PostgreSQL prints it
   * @param description description
   * @param createdBy administrator who added it
   * @param createdAt when it was added
   */
  public record Entry(
      UUID id, String cidr, String description, UUID createdBy, Instant createdAt) {}

  private record Range(byte[] network, int prefix) {}

  /**
   * Creates the allowlist.
   *
   * @param jdbc JDBC template of the application role
   * @param tenants tenant transactions
   * @param cacheTtl cache time to live
   * @param nanoTime monotonic clock
   */
  public OfficeIpAllowlist(
      JdbcTemplate jdbc, TenantTransactions tenants, Duration cacheTtl, LongSupplier nanoTime) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.cacheTtlNanos = cacheTtl.toNanos();
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /**
   * Whether an address is inside one of the institution's office ranges.
   *
   * @param institutionId institution
   * @param address client address
   * @return whether it is an office address
   */
  public boolean contains(UUID institutionId, String address) {
    // Only literals: getByName on anything else would start a DNS lookup.
    if (address == null || !IP_LITERAL.matcher(address).matches()) {
      return false;
    }
    byte[] client;
    try {
      client = InetAddress.getByName(address).getAddress();
    } catch (UnknownHostException e) {
      return false;
    }
    for (Range range : ranges(institutionId)) {
      if (range.network().length == client.length && inRange(client, range)) {
        return true;
      }
    }
    return false;
  }

  private List<Range> ranges(UUID institutionId) {
    Cached cached = cache.get(institutionId);
    long now = nanoTime.getAsLong();
    if (cached != null && cached.expiresAtNanos() - now > 0) {
      return cached.ranges();
    }
    List<Range> ranges =
        tenants.inTenant(
            institutionId,
            () ->
                jdbc.query(
                    "SELECT cidr FROM office_ip_allowlist", (row, i) -> parse(row.getString(1))));
    cache.put(institutionId, new Cached(List.copyOf(ranges), now + cacheTtlNanos));
    return ranges;
  }

  private static Range parse(String cidr) {
    int slash = cidr.indexOf('/');
    try {
      return new Range(
          InetAddress.getByName(cidr.substring(0, slash)).getAddress(),
          Integer.parseInt(cidr.substring(slash + 1)));
    } catch (UnknownHostException e) {
      throw new IllegalStateException("stored CIDR is not an address: " + cidr, e);
    }
  }

  private static boolean inRange(byte[] address, Range range) {
    int bits = range.prefix();
    for (int i = 0; i < address.length && bits > 0; i++, bits -= 8) {
      int mask = bits >= 8 ? 0xff : (0xff << (8 - bits)) & 0xff;
      if ((address[i] & mask) != (range.network()[i] & mask)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Entries of the current institution. Must run in a tenant transaction.
   *
   * @return the entries, newest first
   */
  public List<Entry> list() {
    return jdbc.query(
        """
        SELECT id, cidr::text, description, created_by, created_at FROM office_ip_allowlist
        ORDER BY created_at DESC, id DESC
        """,
        (row, i) ->
            new Entry(
                row.getObject(1, UUID.class),
                row.getString(2),
                row.getString(3),
                row.getObject(4, UUID.class),
                row.getTimestamp(5).toInstant()));
  }

  /**
   * Whether the current institution already lists a network.
   *
   * @param cidr canonical CIDR
   * @return whether it exists
   */
  public boolean exists(String cidr) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM office_ip_allowlist WHERE cidr = ?::cidr)",
            Boolean.class,
            cidr));
  }

  /**
   * Adds a range. Must run in a tenant transaction.
   *
   * @param institutionId institution
   * @param cidr canonical CIDR (validated by the caller)
   * @param description description
   * @param createdBy administrator
   * @param at time
   * @return the stored entry
   */
  public Entry add(
      UUID institutionId, String cidr, String description, UUID createdBy, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO office_ip_allowlist (id, institution_id, cidr, description, created_by, created_at)
        VALUES (?, ?, ?::cidr, ?, ?, ?)
        """,
        id,
        institutionId,
        cidr,
        description,
        createdBy,
        Timestamp.from(at));
    cache.remove(institutionId);
    return find(id).orElseThrow();
  }

  /**
   * An entry of the current institution.
   *
   * @param id entry ID
   * @return the entry
   */
  public Optional<Entry> find(UUID id) {
    return list().stream().filter(e -> e.id().equals(id)).findFirst();
  }

  /**
   * Removes a range. Must run in a tenant transaction.
   *
   * @param institutionId institution
   * @param id entry ID
   * @return whether it existed
   */
  public boolean remove(UUID institutionId, UUID id) {
    boolean removed = jdbc.update("DELETE FROM office_ip_allowlist WHERE id = ?", id) == 1;
    cache.remove(institutionId);
    return removed;
  }
}
