package org.example.controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@code UUID} parameter of an endpoint that receives the calling user: the {@value #HEADER} header,
 * holding a user id or a username (e.g. {@code bob}). Resolved by {@link ActingUserResolver}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActingUser {

    String HEADER = "X-User-Id";
}
