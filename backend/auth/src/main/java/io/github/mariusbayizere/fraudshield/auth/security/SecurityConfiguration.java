package io.github.mariusbayizere.fraudshield.auth.security;

import io.github.mariusbayizere.fraudshield.auth.apikey.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.auth.ratelimit.RateLimiter;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The staff and machine API security chain (FR-07-01, FR-07-04, FR-07-05, D-19, D-27, ADR 0014).
 *
 * <ul>
 *   <li>Stateless: no server session, no form or basic login, Spring's session CSRF off (the API is
 *       bearer or API-key authenticated; the cookie-bearing operations use the double-submit
 *       filter).
 *   <li>{@code X-API-Key} is authenticated before the bearer token; a staff JWT is RS256-verified
 *       with the session check.
 *   <li>Every request is authorised by the contract policy, deny by default; controllers add
 *       method-level rules ({@code @EnableMethodSecurity}).
 *   <li>401 and 403 are RFC 9457 problems with a correlation ID.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

  /**
   * Actuator endpoints are answered only on the management port, which the ingress never routes
   * (ADR 0014 decision 6); on the application port they are denied.
   *
   * @param http security builder
   * @param managementPort management port, -1 when there is none
   * @return the chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Order(1)
  public SecurityFilterChain managementSecurity(
      HttpSecurity http, @Value("${management.server.port:-1}") int managementPort)
      throws Exception {
    return http.securityMatcher("/actuator/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.anyRequest()
                    .access(
                        (authentication, context) ->
                            new org.springframework.security.authorization.AuthorizationDecision(
                                managementPort > 0
                                    && context.getRequest().getLocalPort() == managementPort)))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint()).accessDeniedHandler(denied()))
        .build();
  }

  /**
   * The API chain.
   *
   * @param http security builder
   * @param policy contract policy
   * @param apiKeys API-key authenticator
   * @param limiter rate limiter for failed API keys
   * @param decoder staff JWT decoder
   * @param deniedAudit records refused authenticated callers
   * @return the chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  @Order(2)
  public SecurityFilterChain apiSecurity(
      HttpSecurity http,
      ContractPolicy policy,
      ApiKeyAuthenticator apiKeys,
      RateLimiter limiter,
      JwtDecoder decoder,
      AccessDeniedAudit deniedAudit)
      throws Exception {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("role");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return http.csrf(csrf -> csrf.disable())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .requestCache(cache -> cache.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(apiKeys, limiter), BearerTokenAuthenticationFilter.class)
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(entryPoint())
                    .accessDeniedHandler(audited(deniedAudit)))
        .authorizeHttpRequests(
            auth -> auth.anyRequest().access(new ContractAuthorizationManager(policy)))
        .addFilterAfter(new CsrfDoubleSubmitFilter(policy), AuthorizationFilter.class)
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint()).accessDeniedHandler(audited(deniedAudit)))
        .build();
  }

  private static AccessDeniedHandler audited(AccessDeniedAudit deniedAudit) {
    return (request, response, exception) -> {
      deniedAudit.record(
          request,
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication());
      ProblemWriter.write(ProblemException.forbidden(), request, response);
    };
  }

  private static AuthenticationEntryPoint entryPoint() {
    return (request, response, exception) ->
        ProblemWriter.write(ProblemException.unauthorized(), request, response);
  }

  private static AccessDeniedHandler denied() {
    return (request, response, exception) ->
        ProblemWriter.write(ProblemException.forbidden(), request, response);
  }
}
