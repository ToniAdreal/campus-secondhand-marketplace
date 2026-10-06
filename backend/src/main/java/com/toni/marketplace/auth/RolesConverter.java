package com.toni.marketplace.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Persists a set of roles as a comma-separated VARCHAR (e.g. "USER,ADMIN"). */
@Converter
public class RolesConverter implements AttributeConverter<Set<Role>, String> {

  @Override
  public String convertToDatabaseColumn(Set<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      return Role.USER.name();
    }
    return roles.stream().map(Role::name).sorted().collect(Collectors.joining(","));
  }

  @Override
  public Set<Role> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return EnumSet.of(Role.USER);
    }
    return Arrays.stream(dbData.split(","))
        .map(String::trim)
        .map(Role::valueOf)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
  }
}
