package com.finstep.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityService identity;
    private final HttpSessionSecurityContextRepository contexts;
    public IdentityController(IdentityService identity, HttpSessionSecurityContextRepository contexts) {
        this.identity = identity; this.contexts = contexts;
    }
    public record Registration(@NotBlank @Email @Size(max=255) String email,
                               @NotBlank @Size(min=12,max=72) String password,
                               @NotBlank @Size(max=150) String fullName) { }
    public record Login(@NotBlank @Email @Size(max=255) String email,
                        @NotBlank @Size(max=72) String password) { }
    public record EmailRequest(@NotBlank @Email @Size(max=255) String email) { }
    public record TokenRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) { }
    public record Reset(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
                        @NotBlank @Size(min=12,max=72) String password) { }
    public record ProfilePatch(@Size(max=150) @Pattern(regexp="(?s).*\\S.*") String fullName,
        @Size(max=50) @Pattern(regexp="(?s).*\\S.*") String timezone) { }
    public record CsrfResponse(String token, String headerName) { }

    @GetMapping("/auth/csrf")
    public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken(), token.getHeaderName()); }

    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody Registration body) {
        identity.register(body.email(),body.password(),body.fullName());
    }

    @PostMapping("/auth/login") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        UserPrincipal principal = identity.authenticate(body.email(),body.password());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of());
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication,request,response);
        new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()).onAuthentication(authentication,request,response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
    }

    @PostMapping("/auth/verify-email") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody TokenRequest body) { identity.consumeToken(body.token(),"VERIFY",null); }

    @PostMapping("/auth/resend-verification") @ResponseStatus(HttpStatus.ACCEPTED)
    public void resend(@Valid @RequestBody EmailRequest body) { identity.requestToken(body.email(),"VERIFY"); }

    @PostMapping("/auth/forgot-password") @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgot(@Valid @RequestBody EmailRequest body) { identity.requestToken(body.email(),"RESET"); }

    @PostMapping("/auth/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody Reset body) { identity.consumeToken(body.token(),"RESET",body.password()); }

    @GetMapping("/me")
    public IdentityService.Account me(@AuthenticationPrincipal UserPrincipal principal) { return identity.account(principal.id()); }
    @PatchMapping("/me")
    public IdentityService.Account updateProfile(@AuthenticationPrincipal UserPrincipal owner,@Valid @RequestBody ProfilePatch body) {
        return identity.updateProfile(owner.id(),body.fullName(),body.timezone());
    }

}
