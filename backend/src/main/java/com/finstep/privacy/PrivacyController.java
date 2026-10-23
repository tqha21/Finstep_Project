package com.finstep.privacy;

import com.finstep.identity.UserPrincipal;
import com.finstep.common.security.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/me")
public class PrivacyController {
    private final PrivacyService privacy;
    private final AuthRateLimiter limits;
    public PrivacyController(PrivacyService privacy, AuthRateLimiter limits) { this.privacy = privacy; this.limits = limits; }
    public record Deletion(@NotBlank @Size(max=72) String password,
                           @NotBlank @Pattern(regexp="XÓA TÀI KHOẢN") String confirmation) { }

    @GetMapping("/export")
    public void export(@AuthenticationPrincipal UserPrincipal owner, HttpServletResponse response) throws IOException {
        // Consume the limit before acquiring a long-lived export/deletion transaction connection.
        limits.require("export:" + owner.id(), 3, 300);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"finstep-personal-data.json\"");
        response.setHeader("Cache-Control", "no-store");
        privacy.export(owner, response.getOutputStream());
    }

    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal owner, @Valid @RequestBody Deletion body,
                       HttpServletRequest request, HttpServletResponse response) {
        limits.require("delete:" + owner.id(), 5, 900);
        privacy.delete(owner, body.password());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("SESSION").logout(request, response, authentication);
    }
}
