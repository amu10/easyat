package io.github.easyat.boot3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.easyat.core.AtRepository;
import io.github.easyat.core.BranchRepository;
import io.github.easyat.core.GlobalLockManager;
import io.github.easyat.spring.EasyAtProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import redis.clients.jedis.JedisPool;

class EasyAtRedisAutoConfigurationTest {
    @Test
    void supportsJdbcStorageWithRedisLock() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put("easy-at.storage.type", "jdbc");
            properties.put("easy-at.lock.type", "redis");
            properties.put("easy-at.redis.host", "redis.internal");
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("test", properties));
            context.registerBean(
                    EasyAtProperties.class,
                    () ->
                            Binder.get(context.getEnvironment())
                                    .bind("easy-at", Bindable.of(EasyAtProperties.class))
                                    .get());
            context.register(EasyAtRedisAutoConfiguration.class);
            context.refresh();

            assertNotNull(context.getBean(JedisPool.class));
            assertNotNull(context.getBean(GlobalLockManager.class));
            assertEquals(
                    "redis.internal", context.getBean(EasyAtProperties.class).getRedis().getHost());
            assertTrue(context.getBeansOfType(AtRepository.class).isEmpty());
            assertTrue(context.getBeansOfType(BranchRepository.class).isEmpty());
        }
    }
}
