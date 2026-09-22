package com.example.jwt.domain.module;

import java.util.UUID;

/** The user a module should be assigned to doesn't exist in the user_mgmt_service. */
public class UserNotFoundException extends RuntimeException {

  public UserNotFoundException(UUID userId) {
    super(String.format("User '%s' could not be found", userId));
  }
}
