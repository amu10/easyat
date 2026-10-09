package io.github.easyat.spring;

/** 一次根 AT 事务创建前的灰度决策输入。 */
public final class AtGrayRequest {
    private final String scene;
    private final String key;
    private final String applicationName;
    private final String method;

    public AtGrayRequest(String scene, String key, String applicationName, String method) {
        this.scene = scene;
        this.key = key;
        this.applicationName = applicationName;
        this.method = method;
    }

    public String getScene() {
        return scene;
    }

    public String getKey() {
        return key;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getMethod() {
        return method;
    }
}
