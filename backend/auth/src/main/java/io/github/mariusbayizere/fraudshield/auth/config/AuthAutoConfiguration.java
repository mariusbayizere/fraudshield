package io.github.mariusbayizere.fraudshield.auth.config;

import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.AccountUnlockService;
import io.github.mariusbayizere.fraudshield.auth.account.AuthenticationService;
import io.github.mariusbayizere.fraudshield.auth.account.GoogleSignInService;
import io.github.mariusbayizere.fraudshield.auth.account.LoginThrottle;
import io.github.mariusbayizere.fraudshield.auth.account.OfficeIpAllowlist;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordChangeService;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordHasher;
import io.github.mariusbayizere.fraudshield.auth.account.PasswordResetService;
import io.github.mariusbayizere.fraudshield.auth.account.RegistrationService;
import io.github.mariusbayizere.fraudshield.auth.account.SelfServiceDomains;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyRepository;
import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyService;
import io.github.mariusbayizere.fraudshield.auth.apikey.WebhookUrlValidator;
import io.github.mariusbayizere.fraudshield.auth.crypto.SecretBox;
import io.github.mariusbayizere.fraudshield.auth.crypto.SignedToken;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleIdTokenVerifier;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleIdentityClient;
import io.github.mariusbayizere.fraudshield.auth.google.GoogleTokenVault;
import io.github.mariusbayizere.fraudshield.auth.google.HttpGoogleIdentityClient;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.SigningKeys;
import io.github.mariusbayizere.fraudshield.auth.mail.SmtpStaffMailer;
import io.github.mariusbayizere.fraudshield.auth.mail.StaffMailer;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.InMemoryRateLimiter;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RedisRateLimiter;
import io.github.mariusbayizere.fraudshield.auth.security.ContractPolicy;
import io.github.mariusbayizere.fraudshield.auth.security.SecurityConfiguration;
import io.github.mariusbayizere.fraudshield.auth.security.SessionTokenValidator;
import io.github.mariusbayizere.fraudshield.auth.session.RefreshTokenRepository;
import io.github.mariusbayizere.fraudshield.auth.session.SessionService;
import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import io.github.mariusbayizere.fraudshield.auth.support.BackgroundSubscriber;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.auth.web.AuthController;
import io.github.mariusbayizere.fraudshield.auth.web.CorrelationIdFilter;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemHandler;
import io.github.mariusbayizere.fraudshield.auth.web.SessionCookies;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.DeserializationFeature;

/**
 * Wires staff identity and API authentication. Every secret is required: the context fails to start
 * without the token key, secret-box key, JWT signing keys and API-key peppers (H.1).
 */
@AutoConfiguration(
    afterName = {
      "io.github.mariusbayizere.fraudshield.audit.config.AuditAutoConfiguration",
      "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
      "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
    })
@ConditionalOnBean({JdbcTemplate.class, TenantTransactions.class})
@EnableConfigurationProperties(AuthProperties.class)
@Import({SecurityConfiguration.class, AuthController.class, ProblemHandler.class})
public class AuthAutoConfiguration {

  private static byte[] hex(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " is required");
    }
    return HexFormat.of().parseHex(value);
  }

  /**
   * Refuses unknown JSON fields API-wide: every request schema is closed ({@code
   * additionalProperties: false}, ADR 0011), and an unknown field must be a 400 {@code
   * unknown_field}, never silently dropped (a dropped {@code role} field is how self-elevation bugs
   * hide).
   *
   * @return the customizer
   */
  @Bean
  JsonMapperBuilderCustomizer strictJsonFields() {
    return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  @Bean
  ContractPolicy contractPolicy() {
    return ContractPolicy.load();
  }

  @Bean
  SafeRedis safeRedis(ObjectProvider<StringRedisTemplate> redis) {
    return new SafeRedis(redis.getIfAvailable());
  }

  @Bean(destroyMethod = "close")
  ExecutorService authExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  SigningKeys signingKeys(AuthProperties properties) {
    return SigningKeys.load(
        properties.jwt().signingKeys().stream()
            .map(key -> Map.entry(key.kid(), key.privateKey()))
            .toList());
  }

  @Bean
  AccessTokens accessTokens(SigningKeys keys, AuthProperties properties, Clock clock) {
    AuthProperties.Jwt jwt = properties.jwt();
    return new AccessTokens(keys, jwt.issuer(), jwt.audience(), jwt.accessTokenTtl(), clock);
  }

  @Bean
  StaffAccountRepository staffAccountRepository(JdbcTemplate jdbc) {
    return new StaffAccountRepository(jdbc);
  }

  @Bean
  RefreshTokenRepository refreshTokenRepository(JdbcTemplate jdbc) {
    return new RefreshTokenRepository(jdbc);
  }

  @Bean
  ApiKeyRepository apiKeyRepository(JdbcTemplate jdbc) {
    return new ApiKeyRepository(jdbc);
  }

  @Bean
  SessionStateCache sessionStateCache(
      SafeRedis redis,
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      RefreshTokenRepository tokens,
      AuthProperties properties) {
    return new SessionStateCache(
        redis,
        tenants,
        accounts,
        tokens,
        properties.session().cacheTtl(),
        properties.session().localCacheTtl(),
        System::nanoTime);
  }

  @Bean
  JwtDecoder staffJwtDecoder(AccessTokens tokens, SessionStateCache sessions) {
    return tokens.decoder(List.of(new SessionTokenValidator(sessions)));
  }

  @Bean
  RateLimiter authRateLimiter(SafeRedis redis, Clock clock) {
    return new RedisRateLimiter(redis, new InMemoryRateLimiter(clock), clock);
  }

  @Bean
  OfficeIpAllowlist officeIpAllowlist(
      JdbcTemplate jdbc, TenantTransactions tenants, AuthProperties properties) {
    return new OfficeIpAllowlist(
        jdbc, tenants, properties.rateLimits().officeIpCacheTtl(), System::nanoTime);
  }

  @Bean
  LoginThrottle loginThrottle(
      RateLimiter limiter, OfficeIpAllowlist offices, AuthProperties properties) {
    return new LoginThrottle(limiter, offices, properties.rateLimits());
  }

  @Bean
  PasswordHasher passwordHasher() {
    return new PasswordHasher();
  }

  @Bean
  SignedToken signedToken(AuthProperties properties) {
    return new SignedToken(hex(properties.tokenKeyHex(), "fraudshield.auth.token-key-hex"));
  }

  @Bean
  SecretBox secretBox(AuthProperties properties) {
    return new SecretBox(
        Objects.requireNonNull(
            properties.secretBoxKeyId(), "fraudshield.auth.secret-box-key-id is required"),
        hex(properties.secretBoxKeyHex(), "fraudshield.auth.secret-box-key-hex"));
  }

  @Bean
  SelfServiceDomains selfServiceDomains(AuthProperties properties) {
    return new SelfServiceDomains(properties.selfServiceDomains());
  }

  @Bean
  SessionCookies sessionCookies(AuthProperties properties, Clock clock) {
    return new SessionCookies(properties.session().secureCookies(), clock);
  }

  private static String consoleBaseUrl(AuthProperties properties) {
    return Objects.requireNonNull(
        properties.consoleBaseUrl(), "fraudshield.auth.console-base-url is required");
  }

  @Bean
  @ConditionalOnMissingBean(StaffMailer.class)
  StaffMailer staffMailer(
      ObjectProvider<JavaMailSender> sender,
      AuthProperties properties,
      ExecutorService authExecutor) {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasename("fraudshield/auth/mail");
    messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
    messages.setFallbackToSystemLocale(false);
    return new SmtpStaffMailer(
        Objects.requireNonNull(
            sender.getIfAvailable(), "staff email needs spring.mail.host (Mailpit locally)"),
        messages,
        authExecutor,
        Objects.requireNonNull(properties.mailFrom(), "fraudshield.auth.mail-from is required"),
        consoleBaseUrl(properties));
  }

  @Bean
  SessionService sessionService(
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      RefreshTokenRepository tokens,
      AccessTokens accessTokens,
      SessionStateCache cache,
      AuditLog audit,
      Clock clock,
      AuthProperties properties) {
    return new SessionService(
        tenants,
        accounts,
        tokens,
        accessTokens,
        cache,
        audit,
        clock,
        properties.session().refreshTtl());
  }

  @Bean
  AuthenticationService authenticationService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      PasswordHasher hasher,
      LoginThrottle throttle,
      RateLimiter limiter,
      SessionService sessions,
      AuditLog audit,
      StaffMailer mailer,
      SignedToken tokens,
      Clock clock,
      AuthProperties properties) {
    return new AuthenticationService(
        accounts,
        tenants,
        hasher,
        throttle,
        limiter,
        sessions,
        audit,
        mailer,
        tokens,
        clock,
        properties.lockout(),
        consoleBaseUrl(properties));
  }

  @Bean
  AccountUnlockService accountUnlockService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      SignedToken tokens,
      AuditLog audit,
      Clock clock) {
    return new AccountUnlockService(accounts, tenants, tokens, audit, clock);
  }

  @Bean
  PasswordResetService passwordResetService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      PasswordHasher hasher,
      LoginThrottle throttle,
      SessionService sessions,
      SignedToken tokens,
      StaffMailer mailer,
      AuditLog audit,
      Clock clock,
      AuthProperties properties) {
    return new PasswordResetService(
        accounts,
        tenants,
        jdbc,
        hasher,
        throttle,
        sessions,
        tokens,
        mailer,
        audit,
        clock,
        hex(properties.tokenKeyHex(), "fraudshield.auth.token-key-hex"),
        properties.rateLimits().minimumPublicResponse());
  }

  @Bean
  PasswordChangeService passwordChangeService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      PasswordHasher hasher,
      SessionService sessions,
      AuditLog audit,
      Clock clock) {
    return new PasswordChangeService(accounts, tenants, hasher, sessions, audit, clock);
  }

  @Bean
  RegistrationService registrationService(
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      PasswordHasher hasher,
      LoginThrottle throttle,
      SelfServiceDomains domains,
      StaffMailer mailer,
      AuditLog audit,
      Clock clock,
      AuthProperties properties) {
    return new RegistrationService(
        accounts,
        tenants,
        jdbc,
        hasher,
        throttle,
        domains,
        mailer,
        audit,
        clock,
        consoleBaseUrl(properties),
        properties.rateLimits().minimumPublicResponse());
  }

  private static Map<Integer, byte[]> peppers(AuthProperties properties) {
    Map<Integer, byte[]> peppers = new HashMap<>();
    properties
        .apiKeys()
        .peppers()
        .forEach(
            (version, value) ->
                peppers.put(version, hex(value, "fraudshield.auth.api-keys.peppers")));
    return peppers;
  }

  @Bean
  ApiKeyAuthenticator apiKeyAuthenticator(
      ApiKeyRepository repository,
      TenantTransactions tenants,
      SafeRedis redis,
      AuthProperties properties,
      Clock clock,
      ExecutorService authExecutor) {
    return new ApiKeyAuthenticator(
        repository,
        tenants,
        redis,
        peppers(properties),
        properties.environment(),
        properties.apiKeys().cacheTtl(),
        clock,
        System::nanoTime,
        authExecutor);
  }

  @Bean
  ApiKeyService apiKeyService(
      TenantTransactions tenants,
      ApiKeyRepository repository,
      ApiKeyAuthenticator authenticator,
      SecretBox secretBox,
      AuditLog audit,
      Clock clock,
      AuthProperties properties,
      ObjectProvider<WebhookUrlValidator> webhooks) {
    return new ApiKeyService(
        tenants,
        repository,
        authenticator,
        webhooks.getIfAvailable(() -> new WebhookUrlValidator(InetAddress::getAllByName)),
        secretBox,
        audit,
        clock,
        properties.environment(),
        peppers(properties),
        Objects.requireNonNull(
            properties.apiKeys().currentPepperVersion(),
            "fraudshield.auth.api-keys.current-pepper-version is required"),
        properties.apiKeys().rotationOverlap());
  }

  @Bean
  GoogleTokenVault googleTokenVault(SafeRedis redis, SecretBox box) {
    return new GoogleTokenVault(redis, box);
  }

  @Bean
  @ConditionalOnMissingBean(GoogleIdentityClient.class)
  GoogleIdentityClient googleIdentityClient(AuthProperties properties) {
    AuthProperties.Google google = properties.google();
    if (!google.enabled()) {
      return null;
    }
    return new HttpGoogleIdentityClient(
        URI.create(google.tokenUri()),
        URI.create(google.revokeUri()),
        google.clientId(),
        google.clientSecret(),
        google.timeout());
  }

  @Bean
  GoogleSignInService googleSignInService(
      ObjectProvider<GoogleIdentityClient> client,
      GoogleTokenVault vault,
      StaffAccountRepository accounts,
      TenantTransactions tenants,
      SelfServiceDomains domains,
      SessionService sessions,
      LoginThrottle throttle,
      AuditLog audit,
      Clock clock,
      AuthProperties properties) {
    AuthProperties.Google google = properties.google();
    GoogleIdentityClient http = client.getIfAvailable();
    if (!google.enabled() || http == null) {
      return null;
    }
    return new GoogleSignInService(
        http,
        new GoogleIdTokenVerifier(
            URI.create(google.jwksUri()),
            google.clientId(),
            google.issuers(),
            google.timeout(),
            clock),
        vault,
        accounts,
        tenants,
        domains,
        sessions,
        throttle,
        audit,
        clock,
        google.allowedRedirectUris());
  }

  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  @ConditionalOnBean(RedisConnectionFactory.class)
  BackgroundSubscriber authInvalidationListener(
      RedisConnectionFactory connections, SessionStateCache sessions, ApiKeyAuthenticator apiKeys) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connections);
    container.addMessageListener(
        (message, pattern) ->
            sessions.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(SessionStateCache.CHANNEL));
    container.addMessageListener(
        (message, pattern) ->
            apiKeys.onMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(ApiKeyAuthenticator.CHANNEL));
    container.afterPropertiesSet();
    return new BackgroundSubscriber(container);
  }
}
