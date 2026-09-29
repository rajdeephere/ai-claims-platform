package com.claimsai.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the pollers (job runner, outbox relay, housekeeping). They only ever look at the database, so
 * any number of instances can run them; SKIP LOCKED keeps them from doing the same work.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
