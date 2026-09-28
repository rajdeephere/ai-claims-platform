package com.claimsai.claim.domain;

import com.claimsai.policy.domain.CoverageType;
import com.claimsai.policy.domain.PolicySnapshot.Product;

/** What happened, and which product and coverage would pay for it. */
public enum LossType {
    VEHICLE_COLLISION(Product.AUTO, CoverageType.COLLISION),
    VEHICLE_THEFT(Product.AUTO, CoverageType.COMPREHENSIVE),
    VEHICLE_GLASS(Product.AUTO, CoverageType.GLASS),
    HOME_FIRE(Product.HOME, CoverageType.DWELLING),
    HOME_WATER(Product.HOME, CoverageType.DWELLING),
    HOME_BURGLARY(Product.HOME, CoverageType.CONTENTS);

    private final Product product;
    private final CoverageType coverage;

    LossType(Product product, CoverageType coverage) {
        this.product = product;
        this.coverage = coverage;
    }

    public Product product() {
        return product;
    }

    public CoverageType coverage() {
        return coverage;
    }
}
