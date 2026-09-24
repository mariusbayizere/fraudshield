package io.github.mariusbayizere.fraudshield.notify.vault;

/** The test double honours the contract every real binding must honour. */
class InMemoryKmsTest extends KmsClientContract {

  private final InMemoryKms kms = new InMemoryKms("kek-a", "kek-b");

  @Override
  protected KmsClient client() {
    return kms;
  }

  @Override
  protected String kek() {
    return "kek-a";
  }

  @Override
  protected String otherKek() {
    return "kek-b";
  }

  @Override
  protected KmsClient unreachableClient() {
    InMemoryKms down = new InMemoryKms("kek-a");
    down.down = true;
    return down;
  }
}
