package io.github.mariusbayizere.fraudshield.ingest.web;

import io.github.mariusbayizere.fraudshield.notify.verification.VerificationService;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer verification page (E.7, D-42): server-rendered, no JavaScript, inline CSS, far below
 * 30 KB, large text and 48 px targets, two buttons. It shows masked details only, never says why a
 * token cannot be used beyond "expired or already used", sends no referrer (the token is in the
 * URL) and is never cached. Texts are machine drafts in four languages until reviewed (D-43).
 */
@RestController
public final class VerificationPageController {

  private static final Map<String, Map<String, String>> TEXT =
      Map.of(
          "en",
              Map.of(
                  "title",
                  "Confirm a payment",
                  "question",
                  "Did you make this payment?",
                  "yes",
                  "Yes, this was me",
                  "no",
                  "No, this was not me",
                  "lifted",
                  "Thank you. The payment is unblocked.",
                  "pending",
                  "Thank you. Your answer is recorded and the payment is being unblocked."
                      + " This can take a moment.",
                  "fraud",
                  "Thank you. The payment stays blocked. Call your bank on its official number.",
                  "unusable",
                  "This link has expired or was already used. Call your bank on its official"
                      + " number."),
          "fr",
              Map.of(
                  "title",
                  "Confirmer un paiement",
                  "question",
                  "Avez-vous fait ce paiement ?",
                  "yes",
                  "Oui, c'est moi",
                  "no",
                  "Non, ce n'est pas moi",
                  "lifted",
                  "Merci. Le paiement est débloqué.",
                  "pending",
                  "Merci. Votre réponse est enregistrée et le paiement est en cours de"
                      + " déblocage. Cela peut prendre un instant.",
                  "fraud",
                  "Merci. Le paiement reste bloqué. Appelez votre banque à son numéro officiel.",
                  "unusable",
                  "Ce lien a expiré ou a déjà été utilisé. Appelez votre banque à son numéro"
                      + " officiel."),
          "rw",
              Map.of(
                  "title",
                  "Emeza ubwishyu",
                  "question",
                  "Ni wowe wakoze ubu bwishyu?",
                  "yes",
                  "Yego, ni njye",
                  "no",
                  "Oya, si njye",
                  "lifted",
                  "Murakoze. Ubwishyu bwafunguwe.",
                  "pending",
                  "Murakoze. Igisubizo cyawe cyanditswe kandi ubwishyu buri gufungurwa."
                      + " Bishobora gufata akanya.",
                  "fraud",
                  "Murakoze. Ubwishyu bukomeje guhagarikwa. Hamagara banki yawe.",
                  "unusable",
                  "Iri huzanyo ryarangiye cyangwa ryakoreshejwe. Hamagara banki yawe."),
          "sw",
              Map.of(
                  "title",
                  "Thibitisha malipo",
                  "question",
                  "Je, ulifanya malipo haya?",
                  "yes",
                  "Ndiyo, ni mimi",
                  "no",
                  "Hapana, si mimi",
                  "lifted",
                  "Asante. Malipo yamefunguliwa.",
                  "pending",
                  "Asante. Jibu lako limehifadhiwa na malipo yanafunguliwa. Inaweza kuchukua"
                      + " muda kidogo.",
                  "fraud",
                  "Asante. Malipo yanabaki yamezuiwa. Piga simu benki yako.",
                  "unusable",
                  "Kiungo hiki kimeisha muda au kimetumika. Piga simu benki yako."));

  private static final String CSS =
      "body{font:20px/1.5 sans-serif;margin:16px;max-width:36em}"
          + "button{display:block;width:100%;min-height:48px;margin:12px 0;font-size:20px}"
          + "dl{font-size:22px}dt{font-weight:bold}";

  private final VerificationService verifications;

  /**
   * Creates the controller.
   *
   * @param verifications the verification service
   */
  public VerificationPageController(VerificationService verifications) {
    this.verifications = Objects.requireNonNull(verifications, "verifications");
  }

  /**
   * Shows the page.
   *
   * @param token the link's token
   * @param lang language parameter
   * @param acceptLanguage browser languages
   * @return the page
   * @throws SQLException when the database is unavailable
   */
  @GetMapping("/verify/{token}")
  public ResponseEntity<byte[]> show(
      @PathVariable("token") String token,
      @RequestParam(name = "lang", required = false) String lang,
      @RequestHeader(name = "Accept-Language", required = false) String acceptLanguage)
      throws SQLException {
    String locale = locale(lang, acceptLanguage);
    Map<String, String> t = TEXT.get(locale);
    VerificationService.Lookup lookup = verifications.open(token);
    if (lookup.view().isEmpty()) {
      return page(locale, "<p>" + t.get("unusable") + "</p>");
    }
    VerificationService.View view = lookup.view().get();
    String body =
        "<h1>"
            + t.get("question")
            + "</h1><dl><dt>"
            + escape(view.maskedAccount())
            + "</dt><dd>"
            + escape(view.amount().displayAmount().toPlainString())
            + " "
            + view.amount().currency().name()
            + "</dd><dd>"
            + escape(view.localTime())
            + "</dd></dl>"
            + "<form method=\"post\"><input type=\"hidden\" name=\"lang\" value=\""
            + locale
            + "\">"
            + "<button name=\"answer\" value=\"yes\">"
            + t.get("yes")
            + "</button>"
            + "<button name=\"answer\" value=\"no\">"
            + t.get("no")
            + "</button></form>";
    return page(locale, body);
  }

  /**
   * Records the answer.
   *
   * @param token the link's token
   * @param answer yes or no
   * @param lang language
   * @return the result page
   * @throws SQLException when the database is unavailable
   */
  @PostMapping(path = "/verify/{token}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<byte[]> answer(
      @PathVariable("token") String token,
      @RequestParam(name = "answer") String answer,
      @RequestParam(name = "lang", required = false) String lang)
      throws SQLException {
    String locale = locale(lang, null);
    Map<String, String> t = TEXT.get(locale);
    if (!answer.equals("yes") && !answer.equals("no")) {
      return page(locale, "<p>" + t.get("unusable") + "</p>");
    }
    VerificationService.Answered answered = verifications.answer(token, answer.equals("yes"));
    String message;
    if (answered.unusable().isPresent()) {
      message = t.get("unusable");
    } else if (answered.pending()) {
      // The answer is recorded and the sweep lifts the block within seconds (finding 6): saying
      // "unblocked" here would promise something that has not happened yet.
      message = t.get("pending");
    } else {
      message = answer.equals("yes") ? t.get("lifted") : t.get("fraud");
    }
    return page(locale, "<p>" + message + "</p>");
  }

  static String locale(String lang, String acceptLanguage) {
    if (lang != null && TEXT.containsKey(lang)) {
      return lang;
    }
    if (acceptLanguage != null) {
      for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
        String language = range.getRange().split("-")[0].toLowerCase(Locale.ROOT);
        if (TEXT.containsKey(language)) {
          return language;
        }
      }
    }
    return "en";
  }

  private static ResponseEntity<byte[]> page(String locale, String body) {
    String html =
        "<!doctype html><html lang=\""
            + locale
            + "\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
            + TEXT.get(locale).get("title")
            + "</title><style>"
            + CSS
            + "</style></head><body>"
            + body
            + "</body></html>";
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Content-Type-Options", "nosniff")
        .body(html.getBytes(StandardCharsets.UTF_8));
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
