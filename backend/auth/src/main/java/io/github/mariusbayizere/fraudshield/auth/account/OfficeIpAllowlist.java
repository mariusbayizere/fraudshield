package io.github.mariusbayizere.fraudshield.auth.account;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.persistence.OfficeIpRangeEntity;
import io.github.mariusbayizere.fraudshield.auth.persistence.OfficeIpRangeJpaRepository;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserEntity;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserJpaRepository;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import jakarta.persistence.EntityManager;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

/**
 * An institution's office egress ranges, which get a higher sign-in ceiling (D-26), and their
 * administration. Lookups are cached per institution for a short time; a change evicts the local
 * cache at once and other instances within the cache time to live.
 */
public final class OfficeIpAllowlist {

  private static final Pattern IP_LITERAL =
      Pattern.compile("^([0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]{1,44})$");

  private final OfficeIpRangeJpaRepository ranges;
  private final StaffUserJpaRepository users;
  private final EntityManager entities;
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
   * @param createdBy administrator who added it, fetched with the entry (no N + 1)
   * @param createdAt when it was added
   */
  public record Entry(
      UUID id, String cidr, String description, Creator createdBy, Instant createdAt) {}

  /**
   * The administrator who added a range.
   *
   * @param userId account ID
   * @param firstName first name
   * @param lastName last name
   * @param role role
   */
  public record Creator(UUID userId, String firstName, String lastName, String role) {}

  private record Range(byte[] network, int prefix) {}

  /**
   * Creates the allowlist.
   *
   * @param ranges Spring Data repository of the ranges
   * @param users Spring Data repository of users (creator references)
   * @param entities shared, transaction-bound entity manager
   * @param tenants tenant transactions
   * @param cacheTtl cache time to live
   * @param nanoTime monotonic clock
   */
  public OfficeIpAllowlist(
      OfficeIpRangeJpaRepository ranges,
      StaffUserJpaRepository users,
      EntityManager entities,
      TenantTransactions tenants,
      Duration cacheTtl,
      LongSupplier nanoTime) {
    this.ranges = Objects.requireNonNull(ranges, "ranges");
    this.users = Objects.requireNonNull(users, "users");
    this.entities = Objects.requireNonNull(entities, "entities");
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
    List<Range> loaded =
        tenants.inTenant(
            institutionId,
            () -> ranges.findAllCidrs().stream().map(OfficeIpAllowlist::parse).toList());
    cache.put(institutionId, new Cached(loaded, now + cacheTtlNanos));
    return loaded;
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
   * Entries of the current institution with their creators, in one query. Must run in a tenant
   * transaction.
   *
   * @return the entries, newest first
   */
  public List<Entry> list() {
    return ranges.findAllWithCreator().stream().map(OfficeIpAllowlist::entry).toList();
  }

  private static Entry entry(OfficeIpRangeEntity range) {
    StaffUserEntity creator = range.getCreatedBy();
    return new Entry(
        range.getId(),
        range.getCidr(),
        range.getDescription(),
        new Creator(
            creator.getId(),
            creator.getFirstName(),
            creator.getLastName(),
            creator.getRole().name()),
        range.getCreatedAt());
  }

  /**
   * Whether the current institution already lists a network (compared as {@code cidr}, not text).
   *
   * @param cidr canonical CIDR
   * @return whether it exists
   */
  public boolean exists(String cidr) {
    Object found =
        entities
            .createNativeQuery(
                "SELECT EXISTS (SELECT 1 FROM office_ip_allowlist WHERE cidr = CAST(?1 AS cidr))")
            .setParameter(1, cidr)
            .getSingleResult();
    return Boolean.TRUE.equals(found);
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
    OfficeIpRangeEntity range =
        OfficeIpRangeEntity.create(
            UUID.randomUUID(),
            institutionId,
            cidr,
            description,
            users.getReferenceById(createdBy),
            at);
    entities.persist(range);
    entities.flush();
    AfterCommit.run(() -> cache.remove(institutionId));
    return find(range.getId()).orElseThrow();
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
    Optional<OfficeIpRangeEntity> range = ranges.findById(id);
    range.ifPresent(
        r -> {
          ranges.delete(r);
          entities.flush();
        });
    AfterCommit.run(() -> cache.remove(institutionId));
    return range.isPresent();
  }
}
