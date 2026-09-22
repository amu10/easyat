package io.github.easyat.core;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RowImage implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Map<String, Object> columns;
    public RowImage(Map<String, Object> columns) { this.columns = new LinkedHashMap<String, Object>(columns); }
    public Map<String, Object> getColumns() { return Collections.unmodifiableMap(columns); }
}
