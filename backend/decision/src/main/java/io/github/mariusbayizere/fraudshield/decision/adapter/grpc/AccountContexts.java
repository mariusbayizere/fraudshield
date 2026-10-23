package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.AccountContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the account context the scorer read ({@code ScoringResult.account_context}, ADR 0033) as
 * the JSON object persisted with the score, so a decision can be audited and replayed against the
 * state it saw.
 *
 * <p>This is a rendering, not a computation: nothing here derives a feature (ADR 0061 point 7). It
 * walks the message's descriptor, so a field the contract adds later is kept without a change here.
 * Field names are the proto names; a field with presence that is unset is omitted, which is how the
 * contract says "no source"; a number that is not finite (the scorer's "unknown", PB-37) becomes
 * JSON null; timestamps are ISO-8601 instants.
 */
final class AccountContexts {

  private AccountContexts() {}

  /**
   * The context as a JSON-ready map.
   *
   * @param context what the scorer returned
   * @return field name to value, in the contract's field order
   */
  static Map<String, Object> toMap(AccountContext context) {
    return message(context);
  }

  private static Map<String, Object> message(Message m) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (FieldDescriptor f : m.getDescriptorForType().getFields()) {
      if (f.isRepeated()) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < m.getRepeatedFieldCount(f); i++) {
          values.add(value(f, m.getRepeatedField(f, i)));
        }
        out.put(f.getName(), values);
      } else if (!f.hasPresence() || m.hasField(f)) {
        out.put(f.getName(), value(f, m.getField(f)));
      }
    }
    return out;
  }

  private static Object value(FieldDescriptor f, Object v) {
    return switch (f.getJavaType()) {
      case MESSAGE -> {
        if (v instanceof Timestamp t) {
          yield Instant.ofEpochSecond(t.getSeconds(), t.getNanos()).toString();
        }
        yield message((Message) v);
      }
      case DOUBLE -> Double.isFinite((Double) v) ? v : null;
      case FLOAT -> Float.isFinite((Float) v) ? v : null;
      case INT -> unsigned32(f) ? Integer.toUnsignedLong((Integer) v) : (long) (Integer) v;
      case LONG -> unsigned64(f) ? Long.toUnsignedString((Long) v) : v;
      case ENUM -> ((com.google.protobuf.Descriptors.EnumValueDescriptor) v).getName();
      case BYTE_STRING ->
          java.util.Base64.getEncoder()
              .encodeToString(((com.google.protobuf.ByteString) v).toByteArray());
      default -> v;
    };
  }

  private static boolean unsigned32(FieldDescriptor f) {
    return f.getType() == FieldDescriptor.Type.UINT32
        || f.getType() == FieldDescriptor.Type.FIXED32;
  }

  private static boolean unsigned64(FieldDescriptor f) {
    return f.getType() == FieldDescriptor.Type.UINT64
        || f.getType() == FieldDescriptor.Type.FIXED64;
  }
}
