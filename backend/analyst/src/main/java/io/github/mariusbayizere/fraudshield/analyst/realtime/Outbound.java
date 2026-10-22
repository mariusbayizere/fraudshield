package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One session's bounded outbound queue (ADR 0115 point 8): at most so many frames or bytes,
 * whichever is reached first. An offer never blocks; one that would pass a bound is refused, and
 * the caller closes the session with {@code realtime-overflow}. A failed queue drops what it holds
 * and gives its writer one last frame, the ERROR, before the close.
 */
final class Outbound {

  /**
   * One frame to write: an event body shared by every recipient, written under this session's
   * subscription id, or a control frame already encoded for this session.
   *
   * @param destination the destination, or null for a control frame
   * @param body the event body (JSON), or the whole control frame
   * @param bytes the body's size in bytes
   * @param createdMillis the Kafka record's CreateTime, for the push histogram; 0 for a control
   *     frame
   */
  record Frame(String destination, String body, int bytes, long createdMillis) {}

  private final int maxFrames;
  private final int maxBytes;
  private final InstanceBudget budget;
  private final ArrayDeque<Frame> frames = new ArrayDeque<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition ready = lock.newCondition();
  private long bytes;
  private Frame last;
  private boolean closed;

  Outbound(int maxFrames, int maxBytes, InstanceBudget budget) {
    this.maxFrames = maxFrames;
    this.maxBytes = maxBytes;
    this.budget = budget;
  }

  /**
   * Queues a frame.
   *
   * @param frame the frame
   * @return false when it would pass a bound, or the queue is closed
   */
  boolean offer(Frame frame) {
    lock.lock();
    try {
      if (closed || last != null) {
        return false;
      }
      if (frames.size() >= maxFrames || bytes + frame.bytes() > maxBytes) {
        return false;
      }
      frames.addLast(frame);
      bytes += frame.bytes();
      budget.add(frame.bytes());
      ready.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Drops what is queued and makes {@code error} the last frame written; later offers are refused.
   *
   * @param error the ERROR frame, encoded
   * @return false when the queue had already failed or closed
   */
  boolean fail(Frame error) {
    lock.lock();
    try {
      if (closed || last != null) {
        return false;
      }
      drop();
      last = error;
      ready.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * The next frame, waiting up to {@code timeout}.
   *
   * @param timeout how long to wait
   * @param unit its unit
   * @return the frame; {@link #IDLE} on a timeout; null once closed or after the last frame
   * @throws InterruptedException when interrupted
   */
  Frame take(long timeout, TimeUnit unit) throws InterruptedException {
    long nanos = unit.toNanos(timeout);
    lock.lock();
    try {
      while (true) {
        if (closed) {
          return null;
        }
        if (last != null) {
          Frame error = last;
          closed = true;
          return error;
        }
        Frame next = frames.pollFirst();
        if (next != null) {
          bytes -= next.bytes();
          budget.release(next.bytes());
          return next;
        }
        if (nanos <= 0) {
          return IDLE;
        }
        nanos = ready.awaitNanos(nanos);
      }
    } finally {
      lock.unlock();
    }
  }

  /** Closes the queue and releases its budget. */
  void close() {
    lock.lock();
    try {
      drop();
      closed = true;
      ready.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Bytes queued.
   *
   * @return bytes
   */
  long bytes() {
    lock.lock();
    try {
      return bytes;
    } finally {
      lock.unlock();
    }
  }

  private void drop() {
    budget.release(bytes);
    frames.clear();
    bytes = 0;
  }

  /** Returned by {@link #take} when nothing arrived in time: the writer sends a heart-beat. */
  static final Frame IDLE = new Frame(null, "", 0, 0);

  /** Queued after a DISCONNECT's RECEIPT: the writer closes the socket when it reaches it. */
  static final Frame CLOSE = new Frame(null, "", 0, 0);
}
