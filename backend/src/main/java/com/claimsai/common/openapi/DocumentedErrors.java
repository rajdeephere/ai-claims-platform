package com.claimsai.common.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint-specific error statuses to document with the shared ApiError schema, e.g.
 * {@code @DocumentedErrors({404, 409})}. 400 and 500 are added to every operation, 401 and 403 to every
 * secured one.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DocumentedErrors {
    int[] value();
}
