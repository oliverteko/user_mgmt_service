package com.example.jwt.domain.module;

import java.util.UUID;

/** Response of a successful module assignment. */
public record ModuleAssignmentDTO(UUID userId, ModuleDTO module) {
}
