package com.example.jwt.domain.module;

import java.util.UUID;

/** The module_service answered 404 - the module doesn't exist (not a failure of the service). */
public class ModuleNotFoundException extends RuntimeException {

  public ModuleNotFoundException(UUID moduleId) {
    super(String.format("Module '%s' is not available", moduleId));
  }
}
