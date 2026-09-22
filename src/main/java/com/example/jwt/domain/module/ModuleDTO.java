package com.example.jwt.domain.module;

import java.util.UUID;

/** A module as returned by the module_service ({@code GET /api/v1/modules/{id}}). */
public record ModuleDTO(UUID id, String code, String name, String description) {
}
