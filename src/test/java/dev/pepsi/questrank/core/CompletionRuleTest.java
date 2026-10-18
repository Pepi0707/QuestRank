package dev.pepsi.questrank.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pepsi.questrank.core.score.CompletionRule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * U1 (contrato §13.1): matriz del predicado de finalización (§5), incluidos los casos en que el
 * contador no decide: {@code (false, -1) -> false} y {@code (true, -1) -> true}.
 */
class CompletionRuleTest {

    @ParameterizedTest(name = "teamDataIsCompleted={0}, completionCount={1} -> {2}")
    @CsvSource({
            "false, 0, false",
            "false, -1, false",
            "false, 1, true",
            "false, 3, true",
            "true, 0, true",
            "true, -1, true",
            "true, 1, true"
    })
    void matchesContractUnion(boolean teamDataIsCompleted, int completionCount, boolean expected) {
        if (expected) {
            assertTrue(CompletionRule.isCompleted(teamDataIsCompleted, completionCount));
        } else {
            assertFalse(CompletionRule.isCompleted(teamDataIsCompleted, completionCount));
        }
    }
}
