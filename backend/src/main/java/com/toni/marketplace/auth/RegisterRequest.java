package com.toni.marketplace.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Registration payload. Passwords are never stored — see {@link PasswordService}. */
public record RegisterRequest(
    @NotBlank(message = "username is required")
    @Size(min = 3, max = 60, message = "username must be 3-60 characters")
    String username,

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    @Size(max = 255, message = "email is too long")
    String email,

    @NotBlank(message = "password is required")
    @Size(min = 8, max = 72, message = "password must be 8-72 characters")
    String password) {}
