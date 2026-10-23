package com.finstep.category;

import com.finstep.identity.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryService categories;
    public CategoryController(CategoryService categories) { this.categories=categories; }
    public record Create(@NotBlank @Size(max=100) String name, @NotNull CategoryType type) { }
    public record Patch(@Size(max=100) @Pattern(regexp="(?s).*\\S.*") String name, Boolean archived) { }
    @GetMapping
    public List<CategoryService.Category> list(@AuthenticationPrincipal UserPrincipal user,
        @RequestParam(defaultValue="false") boolean includeArchived, @RequestParam(required=false) CategoryType type) {
        return categories.list(user.id(),includeArchived,type);
    }
    @GetMapping("/{id}")
    public CategoryService.Category get(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id) { return categories.get(user.id(),id); }
    @PostMapping
    public ResponseEntity<CategoryService.Category> create(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody Create body) {
        var category=categories.create(user.id(),body.name(),body.type());
        return ResponseEntity.created(URI.create("/api/v1/categories/"+category.id())).body(category);
    }
    @PatchMapping("/{id}")
    public CategoryService.Category update(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@Valid @RequestBody Patch body) {
        return categories.update(user.id(),id,body.name(),body.archived());
    }
    @PostMapping("/defaults")
    public List<CategoryService.Category> defaults(@AuthenticationPrincipal UserPrincipal user) { return categories.defaults(user.id()); }
}
