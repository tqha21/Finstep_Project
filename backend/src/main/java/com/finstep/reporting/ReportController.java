package com.finstep.reporting;

import com.finstep.identity.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports=reports; }
    @GetMapping("/summary")
    public ReportService.Summary summary(@AuthenticationPrincipal UserPrincipal owner,@RequestParam String month) {
        return reports.summary(owner.id(),month);
    }
}
