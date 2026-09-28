package com.claimsai.common.web;

import com.claimsai.common.error.PreconditionFailedException;
import com.claimsai.common.error.PreconditionRequiredException;

/**
 * ETag / If-Match on top of the JPA {@code @Version} (ADR-0012). The ETag is the entity version in quotes,
 * e.g. {@code "3"}. A state-changing request must send it back in If-Match:
 * <ul>
 *   <li>missing: 428, so "forgot the header" can't silently overwrite someone else's change</li>
 *   <li>different from the current version: 412, the client saw an older state and must reload</li>
 * </ul>
 * The check at load time catches stale clients; {@code @Version} still catches the race between two
 * requests that both passed it (409).
 */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    public static void requireMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("IF_MATCH_REQUIRED",
                    "This request changes the resource: send If-Match with the ETag from your last GET");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        if (!value.equals(String.valueOf(currentVersion))) {
            throw new PreconditionFailedException("VERSION_MISMATCH",
                    "The resource changed since you loaded it (current version " + currentVersion + "). Reload and retry.");
        }
    }
}
