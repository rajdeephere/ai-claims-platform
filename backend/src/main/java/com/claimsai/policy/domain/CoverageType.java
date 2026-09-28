package com.claimsai.policy.domain;

public enum CoverageType {
    /** Auto: damage to the insured vehicle from a collision. */
    COLLISION,
    /** Auto: theft, fire, vandalism, weather. */
    COMPREHENSIVE,
    /** Auto: windscreen and windows. */
    GLASS,
    /** Home: the building. */
    DWELLING,
    /** Home: belongings inside it. */
    CONTENTS
}
