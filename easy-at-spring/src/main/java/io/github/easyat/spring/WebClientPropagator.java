package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.lang.reflect.*;

/**
 * Optional WebClient propagation of the AT context (XID + signed deadline/source) implemented
 * entirely through reflection, so the framework still compiles and runs when {@code spring-webflux}
 * is absent. The returned {@code WebClientCustomizer} is only created when WebClient is on the
 * runtime classpath; otherwise {@link #createCustomizer} returns {@code null} and Spring simply
 * skips the bean.
 *
 * <p><b>为什么用反射：</b>离线构建环境没有 {@code spring-webflux} 依赖，若直接 import
 * {@code ExchangeFilterFunction} 等类型会让编译失败。这里通过 {@code Class.forName} +
 * JDK 动态代理，在运行时才解析 WebClient 的类与方法，因此对 webflux 的依赖是「可选的」：
 * classpath 里有则自动激活，没有则静默跳过，编译期零依赖。
 */
public final class WebClientPropagator {
    private WebClientPropagator() {}

    private static final String CLIENT_REQUEST = "org.springframework.web.reactive.function.client.ClientRequest";
    private static final String EXCHANGE_FUNCTION = "org.springframework.web.reactive.function.client.ExchangeFunction";
    private static final String EXCHANGE_FILTER = "org.springframework.web.reactive.function.client.ExchangeFilterFunction";
    private static final String WEB_CLIENT_BUILDER = "org.springframework.web.reactive.function.client.WebClient$Builder";
    private static final String WEB_CLIENT_CUSTOMIZER = "org.springframework.boot.web.reactive.function.client.WebClientCustomizer";

    /** @return true when the reactive WebClient types are present on the classpath. */
    public static boolean available() {
        try {
            Class.forName(CLIENT_REQUEST);
            Class.forName(WEB_CLIENT_CUSTOMIZER);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Builds a {@code WebClientCustomizer} that injects the current AT context into every outgoing
     * WebClient call. Returns {@code null} when WebClient is not available.
     */
    public static Object createCustomizer(HmacSigner signer, String appName) {
        if (!available()) return null;
        try {
            Class<?> reqCls = Class.forName(CLIENT_REQUEST);
            Class<?> fnCls = Class.forName(EXCHANGE_FUNCTION);
            Class<?> filterIface = Class.forName(EXCHANGE_FILTER);
            Class<?> builderCls = Class.forName(WEB_CLIENT_BUILDER);
            Class<?> customizerIface = Class.forName(WEB_CLIENT_CUSTOMIZER);

            Method mutate = reqCls.getMethod("mutate");
            Method header = builderCls.getMethod("header", String.class, String.class);
            Method build = builderCls.getMethod("build");
            Method exchange = fnCls.getMethod("exchange", reqCls);
            Method filterMethod = filterIface.getMethod("filter", reqCls, fnCls);

            Object filter = Proxy.newProxyInstance(WebClientPropagator.class.getClassLoader(),
                    new Class<?>[] { filterIface },
                    new ExchangeFilterHandler(signer, appName, mutate, header, build, exchange, filterMethod));

            Object customizer = Proxy.newProxyInstance(WebClientPropagator.class.getClassLoader(),
                    new Class<?>[] { customizerIface },
                    new CustomizerHandler(builderCls, filter));
            return customizer;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class ExchangeFilterHandler implements InvocationHandler {
        private final HmacSigner signer;
        private final String appName;
        private final Method mutate, header, build, exchange, filterMethod;
        ExchangeFilterHandler(HmacSigner signer, String appName, Method mutate, Method header,
                              Method build, Method exchange, Method filterMethod) {
            this.signer = signer; this.appName = appName;
            this.mutate = mutate; this.header = header; this.build = build;
            this.exchange = exchange; this.filterMethod = filterMethod;
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!filterMethod.equals(method)) return null;
            Object request = args[0];
            Object next = args[1];
            String xid = AtContext.xid();
            if (xid != null) {
                Object builder = mutate.invoke(request);
                header.invoke(builder, AtTransportHeaders.XID, xid);
                long deadline = System.currentTimeMillis() + 30000L;
                header.invoke(builder, AtTransportHeaders.DEADLINE, Long.toString(deadline));
                header.invoke(builder, AtTransportHeaders.SOURCE, appName);
                if (signer != null && signer.isConfigured())
                    header.invoke(builder, AtTransportHeaders.SIGNATURE, signer.sign(xid, deadline, appName));
                request = build.invoke(builder);
            }
            return exchange.invoke(next, request);
        }
    }

    private static final class CustomizerHandler implements InvocationHandler {
        private final Method filterMethod;
        private final Object filter;
        CustomizerHandler(Class<?> builderCls, Object filter) throws Exception {
            this.filter = filter;
            this.filterMethod = builderCls.getMethod("filter", Class.forName(EXCHANGE_FILTER));
        }
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("customize".equals(method.getName()) && args != null && args.length == 1) {
                filterMethod.invoke(args[0], filter);
            }
            return null;
        }
    }
}
