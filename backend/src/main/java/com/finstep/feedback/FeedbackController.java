package com.finstep.feedback;

import com.finstep.common.security.AuthRateLimiter;
import com.finstep.identity.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {
    private final FeedbackService feedback;
    private final AuthRateLimiter limits;
    public FeedbackController(FeedbackService feedback,AuthRateLimiter limits) { this.feedback=feedback; this.limits=limits; }
    public record Submission(@NotNull FeedbackService.Type type,@NotBlank @Size(min=10,max=2000) String message) { }
    @PostMapping
    public ResponseEntity<FeedbackService.Feedback> create(@AuthenticationPrincipal UserPrincipal owner,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Submission body) {
        limits.require("feedback:"+owner.id(),5,900);
        var result=feedback.create(owner,key,body.type(),body.message());
        return ResponseEntity.status(result.replay()?200:201).body(result.feedback());
    }
    @GetMapping
    public List<FeedbackService.Feedback> recent(@AuthenticationPrincipal UserPrincipal owner) { return feedback.recent(owner.id()); }
}
