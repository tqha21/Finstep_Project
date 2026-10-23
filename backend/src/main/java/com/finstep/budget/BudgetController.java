package com.finstep.budget;

import com.finstep.identity.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {
    private final BudgetService budgets;
    public BudgetController(BudgetService budgets) { this.budgets=budgets; }
    @GetMapping("/{month}")
    public BudgetService.Budget get(@AuthenticationPrincipal UserPrincipal owner,@PathVariable String month) { return budgets.get(owner.id(),month); }
    @PutMapping("/{month}")
    public BudgetService.Budget replace(@AuthenticationPrincipal UserPrincipal owner,@PathVariable String month,
        @Valid @RequestBody BudgetService.Replacement body) { return budgets.replace(owner.id(),month,body); }
}
