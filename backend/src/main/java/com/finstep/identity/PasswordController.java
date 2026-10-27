package com.finstep.identity;

import com.finstep.common.security.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/password")
public class PasswordController {
    private final IdentityService identity;
    private final AuthRateLimiter limits;
    public PasswordController(IdentityService identity, AuthRateLimiter limits) { this.identity=identity; this.limits=limits; }
    public record Change(@NotBlank @Size(max=72) String currentPassword,
                         @NotBlank @Size(min=12,max=72) String newPassword) { }
    @PostMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@AuthenticationPrincipal UserPrincipal owner, @Valid @RequestBody Change body,
                       HttpServletRequest request, HttpServletResponse response) {
        limits.require("password-change:"+owner.id(),5,900);
        identity.changePassword(owner,body.currentPassword(),body.newPassword());
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request,response,authentication);
        new CookieClearingLogoutHandler("SESSION").logout(request,response,authentication);
    }
}
