package com.example.jwt.domain.module;

import com.example.jwt.domain.user.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assigns modules to users. Modules and assignments live in the module_service (and its
 * managed MySQL database) - this service only talks to it over REST, never to its database.
 */
@Service
public class ModuleAssignmentService {

  private final UserService userService;
  private final ModuleServiceClient moduleServiceClient;

  public ModuleAssignmentService(UserService userService,
      ModuleServiceClient moduleServiceClient) {
    this.userService = userService;
    this.moduleServiceClient = moduleServiceClient;
  }

  public ModuleAssignmentDTO assign(UUID userId, UUID moduleId) {
    if (!userService.existsById(userId)) {
      throw new UserNotFoundException(userId);
    }
    // Check availability first, so an unknown module is reported as such before anything
    // is written.
    ModuleDTO module = moduleServiceClient.getModule(moduleId);
    moduleServiceClient.assignModuleToUser(userId, moduleId);
    return new ModuleAssignmentDTO(userId, module);
  }

  public List<ModuleDTO> findAvailableModules() {
    return moduleServiceClient.getModules();
  }
}
