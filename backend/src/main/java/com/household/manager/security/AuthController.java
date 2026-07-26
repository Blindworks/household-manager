package com.household.manager.security;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditService;
import com.household.manager.security.dto.ChangePasswordRequest;
import com.household.manager.security.dto.CurrentUserResponse;
import com.household.manager.security.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

/** Session-Login der Browser-Clients (Frontend + Tablet-WebView). */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final TokenBasedRememberMeServices rememberMeServices;
    private final AuditService auditService;
    private final AppUserService appUserService;
    private final AppUserDetailsService appUserDetailsService;

    @PostMapping("/login")
    public CurrentUserResponse login(@Valid @RequestBody LoginRequest loginRequest,
                                     HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            loginRequest.username(), loginRequest.password()));
            // Session-Fixation verhindern: beim Controller-Login laeuft keine
            // SessionAuthenticationStrategy, die ID muss manuell rotiert werden
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            rememberMeServices.loginSuccess(request, response, authentication);
            auditService.record(AuditActor.user(authentication.getName()), "auth.login", null);
            return CurrentUserResponse.from(authentication);
        } catch (AuthenticationException ex) {
            auditService.record(AuditActor.user(loginRequest.username()), "auth.login-failed", null);
            throw ex;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !(auth instanceof AnonymousAuthenticationToken)) {
            auditService.record("auth.logout", null);
        }
        rememberMeServices.logout(request, response, auth);
        new SecurityContextLogoutHandler().logout(request, response, auth);
    }

    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        return CurrentUserResponse.from(authentication);
    }

    /**
     * Selbst-Passwortwechsel (u. a. der erzwungene Wechsel des
     * Bootstrap-Admins). Session-Principal und Remember-Me-Cookie werden
     * erneuert — sonst truege die Session weiter must_change_password=true
     * und das Cookie waere durch den neuen Passwort-Hash ungueltig.
     */
    @PostMapping("/password")
    public CurrentUserResponse changePassword(@Valid @RequestBody ChangePasswordRequest changeRequest,
                                              Authentication authentication,
                                              HttpServletRequest request, HttpServletResponse response) {
        appUserService.changeOwnPassword(authentication.getName(),
                changeRequest.currentPassword(), changeRequest.newPassword());
        Authentication refreshed = refreshAuthentication(authentication.getName(), request, response);
        auditService.record("auth.change-password", null);
        return CurrentUserResponse.from(refreshed);
    }

    private Authentication refreshAuthentication(String username, HttpServletRequest request,
                                                 HttpServletResponse response) {
        var principal = appUserDetailsService.loadUserByUsername(username);
        Authentication refreshed = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(refreshed);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        rememberMeServices.loginSuccess(request, response, refreshed);
        return refreshed;
    }
}
