package io.github.easyat.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AtGrayDeciderTest {
    @Test
    void legacyAnnotationAlwaysEnablesAt() {
        EasyAtProperties properties = properties("OFF");
        AtGrayDecision decision =
                new PropertiesAtGrayDecider(properties)
                        .decide(new AtGrayRequest("", null, "orders", "place"));
        assertTrue(decision.isEnabled());
        assertEquals("LEGACY_FULL", decision.getReason());
    }

    @Test
    void offStopsOnlyNewGrayTransactions() {
        EasyAtProperties properties = properties("OFF");
        AtGrayDecision decision =
                new PropertiesAtGrayDecider(properties)
                        .decide(new AtGrayRequest("order-place", "u1", "orders", "place"));
        assertFalse(decision.isEnabled());
        assertEquals("GLOBAL_OFF", decision.getReason());
    }

    @Test
    void blacklistWinsOverWhitelistAndPercentage() {
        EasyAtProperties properties = properties("GRAY");
        EasyAtProperties.GrayRule rule = rule(100);
        rule.setWhitelist(new HashSet<String>(Arrays.asList("u1")));
        rule.setBlacklist(new HashSet<String>(Arrays.asList("u1")));
        properties.getGray().getRules().put("order-place", rule);

        AtGrayDecision decision = decide(properties, "u1");
        assertFalse(decision.isEnabled());
        assertEquals("BLACKLIST", decision.getReason());
    }

    @Test
    void whitelistBypassesZeroPercentage() {
        EasyAtProperties properties = properties("GRAY");
        EasyAtProperties.GrayRule rule = rule(0);
        rule.setWhitelist(new HashSet<String>(Arrays.asList("u1")));
        properties.getGray().getRules().put("order-place", rule);

        AtGrayDecision decision = decide(properties, "u1");
        assertTrue(decision.isEnabled());
        assertEquals("WHITELIST", decision.getReason());
    }

    @Test
    void percentageUsesStableBucket() {
        EasyAtProperties properties = properties("GRAY");
        properties.getGray().getRules().put("order-place", rule(37));
        AtGrayDecision first = decide(properties, "user-42");
        AtGrayDecision second = decide(properties, "user-42");

        assertEquals(first.getBucket(), second.getBucket());
        assertEquals(first.isEnabled(), second.isEnabled());
        assertEquals(first.getBucket() < 3700, first.isEnabled());
    }

    @Test
    void missingKeyFailsClosedInGrayMode() {
        EasyAtProperties properties = properties("GRAY");
        properties.getGray().getRules().put("order-place", rule(100));
        AtGrayDecision decision = decide(properties, null);
        assertFalse(decision.isEnabled());
        assertEquals("MISSING_KEY", decision.getReason());
    }

    @Test
    void percentageValidationRejectsInvalidValues() {
        EasyAtProperties.GrayRule rule = new EasyAtProperties.GrayRule();
        assertThrows(IllegalArgumentException.class, () -> rule.setPercentage(-1));
        assertThrows(IllegalArgumentException.class, () -> rule.setPercentage(101));
    }

    private static AtGrayDecision decide(EasyAtProperties properties, String key) {
        return new PropertiesAtGrayDecider(properties)
                .decide(new AtGrayRequest("order-place", key, "orders", "place"));
    }

    private static EasyAtProperties properties(String mode) {
        EasyAtProperties properties = new EasyAtProperties();
        properties.getGray().setMode(mode);
        return properties;
    }

    private static EasyAtProperties.GrayRule rule(int percentage) {
        EasyAtProperties.GrayRule rule = new EasyAtProperties.GrayRule();
        rule.setPercentage(percentage);
        rule.setSalt("v1");
        return rule;
    }
}
