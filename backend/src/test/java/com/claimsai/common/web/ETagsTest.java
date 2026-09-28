package com.claimsai.common.web;

import com.claimsai.common.error.PreconditionFailedException;
import com.claimsai.common.error.PreconditionRequiredException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ETagsTest {

    @Test
    void acceptsTheETagAsSentWithOrWithoutQuotesOrWeakPrefix() {
        assertThatCode(() -> ETags.requireMatch(ETags.of(3), 3)).doesNotThrowAnyException();
        assertThatCode(() -> ETags.requireMatch("3", 3)).doesNotThrowAnyException();
        assertThatCode(() -> ETags.requireMatch("W/\"3\"", 3)).doesNotThrowAnyException();
    }

    @Test
    void missingIfMatchIs428() {
        assertThatThrownBy(() -> ETags.requireMatch(null, 3)).isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> ETags.requireMatch(" ", 3)).isInstanceOf(PreconditionRequiredException.class);
    }

    @Test
    void staleOrGarbageIfMatchIs412() {
        assertThatThrownBy(() -> ETags.requireMatch("\"2\"", 3)).isInstanceOf(PreconditionFailedException.class);
        assertThatThrownBy(() -> ETags.requireMatch("*", 3)).isInstanceOf(PreconditionFailedException.class);
    }
}
