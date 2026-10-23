package io.github.mariusbayizere.fraudshield.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("D-32")
class MerkleRootTest {

  @Test
  void emptyTreeIsTheHashOfTheEmptyString() {
    assertThat(HexFormat.of().formatHex(new MerkleRoot().root()))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void singleLeafIsTheDomainSeparatedLeafHash() throws Exception {
    byte[] row = new byte[32];
    row[0] = 1;
    MerkleRoot tree = new MerkleRoot();
    tree.add(row);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update((byte) 0);
    digest.update(row);
    assertThat(tree.root()).isEqualTo(digest.digest());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 13, 16, 17, 31, 32, 33, 100, 1000})
  void streamingRootEqualsTheRecursiveRfc6962Definition(int leaves) throws Exception {
    Random random = new Random(leaves);
    List<byte[]> rows = new ArrayList<>();
    MerkleRoot tree = new MerkleRoot();
    for (int i = 0; i < leaves; i++) {
      byte[] row = new byte[32];
      random.nextBytes(row);
      rows.add(row);
      tree.add(row);
    }
    assertThat(tree.size()).isEqualTo(leaves);
    assertThat(tree.root()).isEqualTo(reference(rows));
  }

  @Test
  void anyChangedLeafChangesTheRoot() throws Exception {
    List<byte[]> rows = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      byte[] row = new byte[32];
      row[31] = (byte) i;
      rows.add(row);
    }
    byte[] original = reference(rows);
    for (int i = 0; i < rows.size(); i++) {
      rows.get(i)[0] ^= 1;
      assertThat(reference(rows)).isNotEqualTo(original);
      MerkleRoot tree = new MerkleRoot();
      rows.forEach(tree::add);
      assertThat(tree.root()).isNotEqualTo(original);
      rows.get(i)[0] ^= 1;
    }
  }

  /** RFC 6962 section 2.1, written recursively and independently of the streaming code. */
  private static byte[] reference(List<byte[]> leaves) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    if (leaves.size() == 1) {
      digest.update((byte) 0);
      digest.update(leaves.getFirst());
      return digest.digest();
    }
    int split = Integer.highestOneBit(leaves.size() - 1);
    byte[] left = reference(leaves.subList(0, split));
    byte[] right = reference(leaves.subList(split, leaves.size()));
    digest.update((byte) 1);
    digest.update(left);
    digest.update(right);
    return digest.digest();
  }
}
