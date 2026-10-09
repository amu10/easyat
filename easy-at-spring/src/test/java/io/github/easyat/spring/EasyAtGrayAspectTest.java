package io.github.easyat.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.annotation.EasyAtTransactional;
import io.github.easyat.core.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class EasyAtGrayAspectTest {
    @BeforeEach
    void resetContext() {
        AtContext.clear();
    }

    @AfterEach
    void clearContext() {
        AtContext.clear();
    }

    @Test
    void grayOffExecutesBusinessWithoutCreatingXid() {
        Fixture fixture = fixture("OFF");
        assertEquals("ok-u1", fixture.service.gray("u1"));
        assertEquals(0, fixture.repository.creates);
        assertFalse(AtContext.active());
    }

    @Test
    void legacyAnnotationRemainsFullWhenGrayIsOff() {
        Fixture fixture = fixture("OFF");
        assertEquals("legacy-at", fixture.service.legacy());
        assertEquals(1, fixture.repository.creates);
        assertEquals(AtStatus.COMMITTED, fixture.repository.latest().getStatus());
    }

    @Test
    void whitelistCreatesAndCommitsAtTransaction() {
        EasyAtProperties properties = new EasyAtProperties();
        properties.getGray().setMode("GRAY");
        EasyAtProperties.GrayRule rule = new EasyAtProperties.GrayRule();
        rule.getWhitelist().add("u1");
        properties.getGray().getRules().put("order-place", rule);
        Fixture fixture = fixture(properties);

        assertEquals("ok-u1", fixture.service.gray("u1"));
        assertEquals(1, fixture.repository.creates);
        assertEquals(AtStatus.COMMITTED, fixture.repository.latest().getStatus());
    }

    @Test
    void propagatedXidBypassesLocalOffSwitch() {
        Fixture fixture = fixture("OFF");
        AtContext.bind("upstream-xid");

        assertEquals("ok-u1", fixture.service.gray("u1"));
        assertEquals(0, fixture.repository.creates);
        assertEquals("upstream-xid", AtContext.xid());
    }

    private static Fixture fixture(String mode) {
        EasyAtProperties properties = new EasyAtProperties();
        properties.getGray().setMode(mode);
        return fixture(properties);
    }

    private static Fixture fixture(EasyAtProperties properties) {
        MemoryRepository repository = new MemoryRepository();
        AtTransactionManager manager = new AtTransactionManager(repository, record -> {}, 3);
        EasyAtAspect aspect =
                new EasyAtAspect(
                        manager, null, null, new PropertiesAtGrayDecider(properties), properties);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Service());
        factory.addAspect(aspect);
        return new Fixture(factory.getProxy(), repository);
    }

    static class Service {
        @EasyAtTransactional(grayScene = "order-place", grayKey = "#p0")
        public String gray(String userId) {
            return "ok-" + userId;
        }

        @EasyAtTransactional
        public String legacy() {
            return AtContext.active() ? "legacy-at" : "legacy-no-at";
        }
    }

    private static final class Fixture {
        final Service service;
        final MemoryRepository repository;

        Fixture(Service service, MemoryRepository repository) {
            this.service = service;
            this.repository = repository;
        }
    }

    private static final class MemoryRepository implements AtRepository {
        final Map<String, AtTransaction> transactions = new LinkedHashMap<String, AtTransaction>();
        int creates;

        @Override
        public void create(AtTransaction tx) {
            creates++;
            transactions.put(tx.getXid(), tx);
        }

        @Override
        public Optional<AtTransaction> find(String xid) {
            return Optional.ofNullable(transactions.get(xid));
        }

        @Override
        public void save(AtTransaction tx) {
            transactions.put(tx.getXid(), tx);
        }

        @Override
        public List<AtTransaction> recoverable(long now, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<AtTransaction> findByStatus(AtStatus status, int limit) {
            return Collections.emptyList();
        }

        @Override
        public boolean transition(
                String xid, AtStatus expected, long expectedVersion, AtStatus next) {
            AtTransaction tx = transactions.get(xid);
            return tx != null && tx.getStatus() == expected && tx.getVersion() == expectedVersion;
        }

        @Override
        public boolean claimLease(String xid, String owner, long leaseUntil, long now) {
            return true;
        }

        @Override
        public void releaseLease(String xid, String owner) {}

        @Override
        public void updateRecovery(String xid, int retries, long nextRetryAt) {}

        AtTransaction latest() {
            AtTransaction result = null;
            for (AtTransaction tx : transactions.values()) result = tx;
            return result;
        }
    }
}
