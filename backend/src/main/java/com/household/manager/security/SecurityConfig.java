package com.household.manager.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Zentrale Security-Konfiguration: Session-Login fuer Menschen,
 * X-API-Token fuer Maschinen, Rollenmatrix laut Spec
 * docs/superpowers/specs/2026-07-25-user-management-design.md.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    /** Zusatz-Authority aller Service-Token-Requests (Maschinen-Endpunkte). */
    public static final String SERVICE_AUTHORITY = "SERVICE";

    private final ServiceTokenAuthFilter serviceTokenAuthFilter;
    private final DisabledUserSessionFilter disabledUserSessionFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_MEMBER
                ROLE_MEMBER > ROLE_KIOSK
                """);
    }

    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public TokenBasedRememberMeServices rememberMeServices(
            @Value("${auth.remember-me-key:}") String configuredKey,
            AppUserDetailsService userDetailsService) {
        String key = configuredKey;
        if (!StringUtils.hasText(key)) {
            key = UUID.randomUUID().toString();
            log.warn("auth.remember-me-key ist nicht gesetzt — Remember-Me-Logins "
                    + "ueberleben den naechsten Neustart nicht. REMEMBER_ME_KEY setzen!");
        }
        TokenBasedRememberMeServices services = new TokenBasedRememberMeServices(key, userDetailsService);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds((int) Duration.ofDays(90).toSeconds());
        services.setCookieName("HM_REMEMBER");
        return services;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository,
                                           TokenBasedRememberMeServices rememberMeServices) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Default waere der Kontextpfad (/api) — dann sieht document.cookie der
        // unter / laufenden Angular-App das XSRF-TOKEN-Cookie nicht und jeder
        // POST scheitert mit 403 (Angulars XSRF-Interceptor sendet keinen Header)
        csrfTokenRepository.setCookiePath("/");
        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Token-Requests haben keinen Cookie-Kontext -> kein CSRF-Risiko
                        .ignoringRequestMatchers(
                                request -> request.getHeader(ServiceTokenAuthFilter.TOKEN_HEADER) != null))
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                .addFilterBefore(serviceTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(disabledUserSessionFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(new LegacyCsrfCookieCleanupFilter(), CsrfFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                                        "Anmeldung erforderlich."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpStatus.FORBIDDEN, "Forbidden",
                                        "Keine Berechtigung fuer diese Aktion.")))
                .authorizeHttpRequests(auth -> auth
                        // Login/Logout und Actuator-Health bleiben offen
                        .requestMatchers("/v1/auth/login", "/v1/auth/logout").permitAll()
                        .requestMatchers("/management/**").permitAll()
                        // Dokumentierter oeffentlicher Monitoring-Endpunkt, siehe backend/README
                        .requestMatchers("/v1/health/**").permitAll()
                        // Maschinen-Endpunkte: beliebiger gueltiger Service-Token
                        .requestMatchers(HttpMethod.POST,
                                "/v1/vision/recognitions", "/v1/vision/heartbeat").hasAuthority(SERVICE_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/v1/vision/embeddings").hasAuthority(SERVICE_AUTHORITY)
                        .requestMatchers("/v1/tablet-presence/**").hasAuthority(SERVICE_AUTHORITY)
                        // Admin-Bereiche (inkl. bestehender /v1/admin/*-Polling-Controller)
                        // /v1/tractive/home-settings MUSS vor der generischen GET-Regel weiter
                        // unten stehen, sonst duerfte das Kiosk-Tablet die Home-Definition lesen.
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**", "/v1/tractive/login", "/v1/tractive/logout",
                                "/v1/tractive/home-settings", "/v1/presence/settings").hasRole("ADMIN")
                        // Kategorien: lesen darf jeder Angemeldete ueber die generische
                        // GET-Regel weiter unten, aendern nur ADMIN. Die Regeln muessen
                        // methodenspezifisch sein — ein methodenloser Matcher wuerde das
                        // Lesen fuer das Wandtablet mitsperren.
                        .requestMatchers(HttpMethod.POST, "/v1/calendar/categories").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/calendar/categories/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/calendar/categories/*").hasRole("ADMIN")
                        // Netzwerkgeraete pflegen ist ADMIN, lesen darf jeder Angemeldete ueber die
                        // generische GET-Regel weiter unten. Methodenspezifisch aus demselben Grund
                        // wie bei den Kalender-Kategorien: das Wandtablet soll weiter lesen duerfen.
                        .requestMatchers(HttpMethod.POST, "/v1/network/devices").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/network/devices/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/network/devices/*").hasRole("ADMIN")
                        // Anwesenheits-Geraete pflegen ist ADMIN. Anders als bei den
                        // Netzwerk-Geraeten (dort liest die Wandtablet-Netzwerkansicht die Liste)
                        // ist hier auch das LESEN ADMIN: keine Tablet-Ansicht braucht die
                        // Geraeteliste (die Dashboard-Kachel spricht nur GET /status), und die
                        // Zeilen tragen die privaten Handy-IPs der Haushaltsmitglieder — die
                        // Matcher-Reihenfolge (vor der generischen GET-Regel) ist hier deshalb
                        // tragend, nicht nur Stil.
                        .requestMatchers(HttpMethod.GET, "/v1/presence/devices").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/presence/devices").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/presence/devices/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/presence/devices/*").hasRole("ADMIN")
                        // Manueller Abruf lebt bewusst NICHT in der KIOSK-POST-Whitelist weiter
                        // unten (anders als /v1/tractive/pets/refresh oder /v1/network/speedtest):
                        // er sitzt nur auf der Admin-Seite und kann als sequentielle Probe aller
                        // aktiven Geraete mehrere Sekunden des Request-Threads belegen.
                        .requestMatchers(HttpMethod.POST, "/v1/presence/refresh").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/utility-prices/**").hasRole("KIOSK")
                        .requestMatchers("/v1/utility-prices/**").hasRole("ADMIN")
                        // Finanzdaten sind privat — nicht fuers Kiosk-Tablet
                        .requestMatchers("/v1/finance/**").hasRole("MEMBER")
                        // KIOSK-Whitelist: Dashboard lesen + Schalter/Modi/Nuki
                        // (LOCK-only fuer KIOSK erzwingt der NukiController)
                        // /v1/tractive/pets/refresh schaltet nichts, es zieht nur Daten —
                        // ohne KIOSK waere der Aktualisieren-Knopf auf dem Wandtablet tot.
                        // /v1/network/speedtest ebenso: zieht nur Daten, schaltet nichts —
                        // sonst waere der Speedtest-Knopf auf dem Wandtablet tot.
                        // /v1/blink/cameras/*/snapshot zieht nur ein Standbild, es
                        // schaltet nichts — sonst waere der Schnappschuss-Knopf auf
                        // dem Wandtablet tot. Scharf/Unscharf faellt bewusst auf
                        // anyRequest -> MEMBER durch.
                        .requestMatchers(HttpMethod.POST, "/v1/switches/*/toggle",
                                "/v1/modes/*/toggle", "/v1/nuki/locks/*/actions",
                                "/v1/auth/password", "/v1/tractive/pets/refresh",
                                "/v1/system/reboot", "/v1/network/speedtest",
                                "/v1/blink/cameras/*/snapshot").hasRole("KIOSK")
                        .requestMatchers(HttpMethod.GET, "/v1/**", "/energy/**", "/devices/**",
                                "/kasa/**", "/tapo/**", "/meross/**", "/shelly/**").hasRole("KIOSK")
                        // Alles Uebrige (Geraete schalten, Kalender/Zaehler pflegen, Ansagen ...)
                        .anyRequest().hasRole("MEMBER"));
        return http.build();
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                            String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(error)
                .message(message)
                .build());
    }
}
