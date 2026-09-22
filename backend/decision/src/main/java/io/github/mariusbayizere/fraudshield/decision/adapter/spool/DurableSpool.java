package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bounded, append-only local disk spool (D-15).
 *
 * <p>Records are framed as {@code [length:int][crc32c:int][payload]} in segment files named by
 * their first offset. One writer thread takes every pending record, writes them, calls {@code
 * fsync} once and only then completes their futures (group commit): under load each fsync covers
 * everything that arrived during the previous one, so fsyncs run back-to-back with no idle timer.
 * Only fsynced records are visible to readers.
 *
 * <p>Several named consumers (the Kafka publisher and the PostgreSQL writer) read the same records
 * in order and commit their own positions, persisted atomically; a segment is deleted once every
 * consumer has passed it. On open, a torn final frame in the last segment (a crash mid-write) is
 * truncated; a bad frame anywhere else is corruption and the spool refuses to open rather than skip
 * data. When unconsumed bytes would exceed the bound, {@link #append} fails at once: the caller
 * refuses the request instead of losing it.
 */
public final class DurableSpool implements AutoCloseable {

  /**
   * Spool sizing.
   *
   * @param maxBytes most unconsumed bytes on disk
   * @param segmentBytes segment size at which a new segment is started
   * @param queueCapacity records buffered in memory in front of the writer
   */
  public record Settings(long maxBytes, long segmentBytes, int queueCapacity) {

    /** 1 GiB bound, 64 MiB segments, 10,000 buffered records. */
    public static final Settings DEFAULTS = new Settings(1L << 30, 64L << 20, 10_000);

    /** Validates. */
    public Settings {
      if (maxBytes <= 0 || segmentBytes <= 0 || queueCapacity <= 0) {
        throw new IllegalArgumentException("spool sizes must be positive");
      }
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(DurableSpool.class);
  private static final int HEADER = 8;
  private static final int MAX_RECORD = 16 << 20;
  private static final String SEGMENT_SUFFIX = ".seg";
  private static final String CHECKPOINT_SUFFIX = ".ckpt";
  private static final byte[] NO_FRAME = new byte[0];

  private final Path directory;
  private final Settings settings;
  private final Set<String> consumers;
  private final BlockingQueue<Pending> queue;
  private final NavigableMap<Long, Path> segments = new ConcurrentSkipListMap<>();
  private final Map<String, AtomicLong> committed = new ConcurrentHashMap<>();
  private final AtomicLong durableEnd = new AtomicLong();
  private final AtomicLong pendingBytes = new AtomicLong();
  private final Thread writer;
  private final AtomicLong fsyncCount = new AtomicLong();
  private final AtomicLong lastFsyncNanos = new AtomicLong();
  private volatile boolean closed;
  private FileChannel active;
  private volatile long activeBase;

  /** Queued, taken by the writer, or withdrawn by a caller that stopped waiting. */
  private record Pending(byte[] payload, CompletableFuture<Long> done, AtomicInteger state) {
    static final int QUEUED = 0;
    static final int TAKEN = 1;
    static final int WITHDRAWN = 2;
  }

  /** How long a caller keeps waiting for a record the writer has already taken. */
  static final Duration TAKEN_GRACE = Duration.ofSeconds(5);

  /**
   * Opens (and recovers) a spool.
   *
   * @param directory spool directory, created if missing
   * @param settings sizing
   * @param consumers names of the consumers that must pass a record before it is deleted
   */
  public DurableSpool(Path directory, Settings settings, Set<String> consumers) {
    this(directory, settings, consumers, true);
  }

  /**
   * Opens a spool, optionally without starting its writer (tests start it with {@link
   * #startWriter()} to hold records in the queue).
   */
  DurableSpool(Path directory, Settings settings, Set<String> consumers, boolean startWriter) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.consumers = Set.copyOf(consumers);
    if (this.consumers.isEmpty()) {
      throw new IllegalArgumentException("a spool needs at least one consumer");
    }
    this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
    try {
      Files.createDirectories(directory);
      recover();
    } catch (IOException e) {
      throw new UncheckedIOException("could not open the spool at " + directory, e);
    }
    this.writer = Thread.ofPlatform().name("spool-writer").daemon(true).unstarted(this::writeLoop);
    if (startWriter) {
      this.writer.start();
    }
  }

  /** Starts the writer of a spool opened without one. */
  void startWriter() {
    writer.start();
  }

  /**
   * Appends a record; the future completes with its offset once it is fsynced.
   *
   * @param payload the record
   * @return completion after fsync
   * @throws SpoolFullException when the bound would be exceeded or the spool is closed
   */
  public CompletableFuture<Long> append(byte[] payload) {
    if (payload.length == 0 || payload.length > MAX_RECORD) {
      throw new IllegalArgumentException("record size out of range: " + payload.length);
    }
    if (closed) {
      throw new SpoolFullException("the spool is closed");
    }
    long size = (long) payload.length + HEADER;
    if (durableEnd.get() + pendingBytes.get() + size - minimumCommitted() > settings.maxBytes()) {
      throw new SpoolFullException("the spool holds its maximum of unconsumed bytes");
    }
    return enqueue(payload, size).done();
  }

  private Pending enqueue(byte[] payload, long size) {
    Pending pending =
        new Pending(payload.clone(), new CompletableFuture<>(), new AtomicInteger(Pending.QUEUED));
    pendingBytes.addAndGet(size);
    if (!queue.offer(pending)) {
      pendingBytes.addAndGet(-size);
      throw new SpoolFullException("the in-memory buffer in front of the spool is full");
    }
    return pending;
  }

  /**
   * Appends and waits for the fsync.
   *
   * @param payload the record
   * @param timeout how long to wait
   * @return the record's offset
   * @throws SpoolFullException when the record was not made durable in time
   */
  public long appendAndWait(byte[] payload, Duration timeout) {
    if (payload.length == 0 || payload.length > MAX_RECORD) {
      throw new IllegalArgumentException("record size out of range: " + payload.length);
    }
    if (closed) {
      throw new SpoolFullException("the spool is closed");
    }
    long size = (long) payload.length + HEADER;
    if (durableEnd.get() + pendingBytes.get() + size - minimumCommitted() > settings.maxBytes()) {
      throw new SpoolFullException("the spool holds its maximum of unconsumed bytes");
    }
    Pending pending = enqueue(payload, size);
    try {
      return pending.done().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException late) {
      // Withdrawn before the writer took it: the record will never be written, so the caller may
      // treat it as not recorded. Taken already: its write is in progress and is waited for.
      if (pending.state().compareAndSet(Pending.QUEUED, Pending.WITHDRAWN)) {
        pendingBytes.addAndGet(-size);
        throw new SpoolFullException("the spool did not take the record in time; it was withdrawn");
      }
      return awaitTaken(pending);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpoolOutcomeUnknownException("interrupted while the record may be written", e);
    } catch (ExecutionException e) {
      throw new SpoolFullException("the spool did not make the record durable: " + e.getCause());
    }
  }

  private long awaitTaken(Pending pending) {
    try {
      return pending.done().get(TAKEN_GRACE.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException e) {
      throw new SpoolFullException("the spool did not make the record durable: " + e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpoolOutcomeUnknownException("interrupted while the record may be written", e);
    } catch (TimeoutException e) {
      throw new SpoolOutcomeUnknownException("the record's fsync did not finish in time", e);
    }
  }

  /**
   * Reads durable records from a position.
   *
   * @param from position to start at (a consumer's committed position)
   * @param maxRecords most records to return
   * @return records in order; empty when nothing durable follows {@code from}
   * @throws IllegalStateException when a durable record fails its checksum
   */
  public List<SpoolRecord> read(long from, int maxRecords) {
    List<SpoolRecord> records = new ArrayList<>();
    long position = from;
    long end = durableEnd.get();
    while (records.size() < maxRecords && position < end) {
      Map.Entry<Long, Path> segment = segments.floorEntry(position);
      if (segment == null) {
        throw new IllegalStateException("position " + position + " precedes the spool");
      }
      try (FileChannel channel = FileChannel.open(segment.getValue(), StandardOpenOption.READ)) {
        long local = position - segment.getKey();
        long segmentEnd = Math.min(channel.size(), end - segment.getKey());
        if (local >= segmentEnd) {
          Long next = segments.higherKey(segment.getKey());
          if (next == null) {
            break;
          }
          position = next;
          continue;
        }
        while (records.size() < maxRecords && local < segmentEnd) {
          byte[] payload = readFrame(channel, local, segmentEnd);
          if (payload.length == 0) {
            throw new IllegalStateException(
                "corrupt durable record at " + (segment.getKey() + local));
          }
          long next = local + HEADER + payload.length;
          records.add(new SpoolRecord(segment.getKey() + local, segment.getKey() + next, payload));
          local = next;
        }
        position = segment.getKey() + local;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return records;
  }

  /**
   * A consumer's committed position.
   *
   * @param consumer consumer name
   * @return the position to read from next
   */
  public long committed(String consumer) {
    return counter(consumer).get();
  }

  /**
   * Commits a consumer's position durably and deletes segments every consumer has passed.
   *
   * @param consumer consumer name
   * @param position the {@link SpoolRecord#nextOffset()} of the last record consumed
   */
  public synchronized void commit(String consumer, long position) {
    AtomicLong counter = counter(consumer);
    if (position < counter.get() || position > durableEnd.get()) {
      throw new IllegalArgumentException("commit position " + position + " is out of order");
    }
    try {
      Path file = directory.resolve(consumer + CHECKPOINT_SUFFIX);
      Path temporary = directory.resolve(consumer + CHECKPOINT_SUFFIX + ".tmp");
      try (FileChannel channel =
          FileChannel.open(
              temporary,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING)) {
        channel.write(ByteBuffer.wrap(Long.toString(position).getBytes(StandardCharsets.US_ASCII)));
        channel.force(true);
      }
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
      syncDirectory();
      counter.set(position);
      deleteConsumedSegments();
    } catch (IOException e) {
      throw new UncheckedIOException("could not commit the spool position", e);
    }
  }

  /**
   * Bytes durable but not yet committed by the slowest consumer ({@code fs_spool_depth}).
   *
   * @return unconsumed bytes
   */
  public long depthBytes() {
    return durableEnd.get() - minimumCommitted();
  }

  /**
   * Bytes durable but not yet committed by one consumer.
   *
   * @param consumer consumer name
   * @return that consumer's lag in bytes
   */
  public long lagBytes(String consumer) {
    return durableEnd.get() - committed(consumer);
  }

  /**
   * The end of the durable records.
   *
   * @return position after the last fsynced record
   */
  public long durableEnd() {
    return durableEnd.get();
  }

  /**
   * Number of fsyncs so far, for the group-commit measurement.
   *
   * @return fsync count
   */
  public long fsyncCount() {
    return fsyncCount.get();
  }

  /**
   * Duration of the last fsync.
   *
   * @return nanoseconds
   */
  public long lastFsyncNanos() {
    return lastFsyncNanos.get();
  }

  @Override
  public void close() {
    closed = true;
    writer.interrupt();
    try {
      writer.join(TimeUnit.SECONDS.toMillis(10));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    List<Pending> left = new ArrayList<>();
    queue.drainTo(left);
    for (Pending pending : left) {
      pending.done().completeExceptionally(new SpoolFullException("the spool closed"));
    }
    closeActive();
  }

  private AtomicLong counter(String consumer) {
    if (!consumers.contains(consumer)) {
      throw new IllegalArgumentException("unknown spool consumer " + consumer);
    }
    return committed.get(consumer);
  }

  private long minimumCommitted() {
    long minimum = Long.MAX_VALUE;
    for (AtomicLong position : committed.values()) {
      minimum = Math.min(minimum, position.get());
    }
    return minimum;
  }

  private void writeLoop() {
    List<Pending> batch = new ArrayList<>();
    while (!closed) {
      try {
        batch.add(queue.take());
      } catch (InterruptedException e) {
        break;
      }
      queue.drainTo(batch);
      batch.removeIf(pending -> !pending.state().compareAndSet(Pending.QUEUED, Pending.TAKEN));
      if (batch.isEmpty()) {
        continue;
      }
      long start = durableEnd.get();
      try {
        final long end = writeBatch(batch);
        long before = System.nanoTime();
        active.force(false);
        lastFsyncNanos.set(System.nanoTime() - before);
        fsyncCount.incrementAndGet();
        durableEnd.set(end);
        long position = start;
        for (Pending pending : batch) {
          pendingBytes.addAndGet(-(pending.payload().length + HEADER));
          pending.done().complete(position);
          position += pending.payload().length + HEADER;
        }
      } catch (IOException | RuntimeException e) {
        LOG.error("spool write failed; the batch is refused and the tail rolled back", e);
        rollBack(start);
        for (Pending pending : batch) {
          pendingBytes.addAndGet(-(pending.payload().length + HEADER));
          pending.done().completeExceptionally(e);
        }
      }
      batch.clear();
    }
  }

  private long writeBatch(List<Pending> batch) throws IOException {
    long end = durableEnd.get();
    for (Pending pending : batch) {
      if (end - activeBase >= settings.segmentBytes()) {
        active.force(false);
        closeActive();
        openSegment(end);
      }
      CRC32C crc = new CRC32C();
      crc.update(pending.payload());
      ByteBuffer frame = ByteBuffer.allocate(HEADER + pending.payload().length);
      frame.putInt(pending.payload().length).putInt((int) crc.getValue()).put(pending.payload());
      frame.flip();
      while (frame.hasRemaining()) {
        active.write(frame, end - activeBase + frame.position());
      }
      end += HEADER + pending.payload().length;
    }
    return end;
  }

  private void rollBack(long durable) {
    try {
      if (activeBase > durable) {
        closeActive();
        Files.deleteIfExists(segments.remove(activeBase));
        Map.Entry<Long, Path> last = segments.floorEntry(durable);
        activeBase = last.getKey();
        active =
            FileChannel.open(last.getValue(), StandardOpenOption.WRITE, StandardOpenOption.READ);
      }
      active.truncate(durable - activeBase);
    } catch (IOException e) {
      LOG.error("spool rollback failed", e);
    }
  }

  private void recover() throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SEGMENT_SUFFIX)) {
      for (Path file : files) {
        String name = String.valueOf(file.getFileName());
        segments.put(
            Long.parseLong(name.substring(0, name.length() - SEGMENT_SUFFIX.length())), file);
      }
    }
    long end = 0;
    for (Map.Entry<Long, Path> segment : segments.entrySet()) {
      boolean last = segment.getKey().equals(segments.lastKey());
      try (FileChannel channel =
          FileChannel.open(segment.getValue(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        long size = channel.size();
        long local = 0;
        while (local < size) {
          byte[] payload = readFrame(channel, local, size);
          if (payload.length == 0) {
            break;
          }
          local += HEADER + payload.length;
        }
        if (local < size) {
          if (!last) {
            throw new IllegalStateException(
                "spool segment "
                    + segment.getValue()
                    + " is corrupt before its end; refusing to skip data");
          }
          LOG.warn("truncating a torn record at the end of the last spool segment");
          channel.truncate(local);
          channel.force(true);
        }
        end = segment.getKey() + local;
      }
    }
    if (segments.isEmpty()) {
      openSegment(0);
    } else {
      activeBase = segments.lastKey();
      active =
          FileChannel.open(
              segments.lastEntry().getValue(), StandardOpenOption.WRITE, StandardOpenOption.READ);
    }
    durableEnd.set(end);
    long first = segments.firstKey();
    for (String consumer : consumers) {
      Path file = directory.resolve(consumer + CHECKPOINT_SUFFIX);
      long position =
          Files.exists(file)
              ? Long.parseLong(Files.readString(file, StandardCharsets.US_ASCII).strip())
              : first;
      if (position > end) {
        throw new IllegalStateException("checkpoint of " + consumer + " is past the spool end");
      }
      committed.put(consumer, new AtomicLong(Math.max(position, first)));
    }
  }

  private void openSegment(long base) throws IOException {
    Path file = directory.resolve(String.format("%020d%s", base, SEGMENT_SUFFIX));
    active =
        FileChannel.open(
            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
    activeBase = base;
    segments.put(base, file);
    // A new file's data is fsynced with the batch, but on some filesystems its directory entry is
    // not durable until the directory itself is fsynced: a crash could lose a freshly rolled
    // segment and the records acknowledged in it (Principal Review finding 17).
    syncDirectory();
  }

  /**
   * Makes the directory's own entries durable, after creating a segment or renaming a checkpoint.
   */
  private void syncDirectory() {
    try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
      dir.force(true);
    } catch (IOException e) {
      // Some filesystems refuse to open or fsync a directory; the data fsync still happened.
      LOG.debug("could not fsync the spool directory", e);
    }
  }

  private void closeActive() {
    if (active != null) {
      try {
        active.close();
      } catch (IOException e) {
        LOG.warn("could not close a spool segment", e);
      }
    }
  }

  private void deleteConsumedSegments() throws IOException {
    long minimum = minimumCommitted();
    for (Long base : List.copyOf(segments.headMap(activeBase, false).keySet())) {
      Long next = segments.higherKey(base);
      if (next != null && next <= minimum) {
        Files.deleteIfExists(segments.remove(base));
      }
    }
  }

  private static byte[] readFrame(FileChannel channel, long local, long limit) throws IOException {
    if (limit - local < HEADER) {
      return NO_FRAME;
    }
    ByteBuffer header = ByteBuffer.allocate(HEADER);
    readFully(channel, header, local);
    header.flip();
    int length = header.getInt();
    final int crc = header.getInt();
    if (length <= 0 || length > MAX_RECORD || limit - local - HEADER < length) {
      return NO_FRAME;
    }
    ByteBuffer body = ByteBuffer.allocate(length);
    readFully(channel, body, local + HEADER);
    CRC32C check = new CRC32C();
    check.update(body.array());
    return (int) check.getValue() == crc ? body.array() : NO_FRAME;
  }

  private static void readFully(FileChannel channel, ByteBuffer buffer, long position)
      throws IOException {
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, position + buffer.position()) < 0) {
        throw new IOException("unexpected end of spool segment");
      }
    }
  }
}
