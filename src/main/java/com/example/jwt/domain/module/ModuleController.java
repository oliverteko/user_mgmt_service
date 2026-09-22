package com.example.jwt.domain.module;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModuleController {

  private final ModuleAssignmentService moduleAssignmentService;

  public ModuleController(ModuleAssignmentService moduleAssignmentService) {
    this.moduleAssignmentService = moduleAssignmentService;
  }

  /** Modules that can be assigned, as provided by the module_service. */
  @GetMapping("/modules")
  public ResponseEntity<List<ModuleDTO>> retrieveAvailableModules() {
    return ResponseEntity.ok(moduleAssignmentService.findAvailableModules());
  }

  /**
   * Assigns a module to a user. Idempotent. Users may assign modules to themselves; assigning to
   * someone else needs the USER_MODIFY authority.
   *
   * <ul>
   *   <li>200 - assigned (or already was)</li>
   *   <li>400 - malformed id</li>
   *   <li>404 - user or module doesn't exist</li>
   *   <li>503 - module_service unavailable (timeouts/retries exhausted, circuit breaker open)</li>
   * </ul>
   */
  @PutMapping("/users/{userId}/modules/{moduleId}")
  @PreAuthorize("hasAuthority('USER_MODIFY') || #userId == authentication.principal.user.id")
  public ResponseEntity<ModuleAssignmentDTO> assignModule(@PathVariable UUID userId,
      @PathVariable UUID moduleId) {
    return ResponseEntity.ok(moduleAssignmentService.assign(userId, moduleId));
  }
}
