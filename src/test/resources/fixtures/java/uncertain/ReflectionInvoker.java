package com.charmnight.linkgraph.fixtures.uncertain;

import java.lang.reflect.Proxy;
import java.util.ServiceLoader;

public class ReflectionInvoker {
    private final ReflectionTarget proxyTarget = new ReflectionTarget();

    public String render(String input) {
        invokeReflectively(input);
        discoverProviders();
        return callThroughProxy(input);
    }

    public String invokeReflectively(String input) {
        try {
            return (String) Class.forName("com.charmnight.linkgraph.fixtures.uncertain.ReflectionTarget")
                .getDeclaredMethod("handle", String.class)
                .invoke(new ReflectionTarget(), input);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void discoverProviders() {
        ServiceLoader.load(PluginHandler.class);
    }

    public String callThroughProxy(String input) {
        ProxiedHandler proxy = (ProxiedHandler) Proxy.newProxyInstance(
            ProxiedHandler.class.getClassLoader(),
            new Class<?>[] { ProxiedHandler.class },
            (currentProxy, method, args) -> proxyTarget.handle((String) args[0])
        );
        return proxy.handle(input);
    }
}

class ReflectionTarget {
    String handle(String input) {
        return input.trim();
    }
}

interface PluginHandler {
    String id();
}

class AlphaPluginHandler implements PluginHandler {
    @Override
    public String id() {
        return "alpha";
    }
}

class BetaPluginHandler implements PluginHandler {
    @Override
    public String id() {
        return "beta";
    }
}

interface ProxiedHandler {
    String handle(String input);
}

class ProxiedHandlerImpl implements ProxiedHandler {
    @Override
    public String handle(String input) {
        return input.toUpperCase();
    }
}
