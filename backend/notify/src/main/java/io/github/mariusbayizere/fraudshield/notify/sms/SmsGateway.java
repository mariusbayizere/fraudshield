package io.github.mariusbayizere.fraudshield.notify.sms;

import java.io.IOException;

/** An SMS provider (D-51: Africa's Talking in production, a local fake in tests). */
@FunctionalInterface
public interface SmsGateway {

  /**
   * Sends one message.
   *
   * @param senderId the institution's registered sender ID
   * @param phoneE164 recipient
   * @param text GSM-7 text, one segment
   * @return the provider's message reference
   * @throws IOException when the provider did not accept the message
   */
  String send(String senderId, String phoneE164, String text) throws IOException;
}
