package org.ovirt.engine.core.common.businessentities;

import java.util.HashMap;
import java.util.Map;

public enum InterfaceStatus {
    None(0),
    Up(1),
    Down(2);

    private int intValue;
    private static Map<Integer, InterfaceStatus> mappings;

    static {
        mappings = new HashMap<Integer, InterfaceStatus>();
        for (InterfaceStatus error : values()) {
            mappings.put(error.getValue(), error);
        }
    }

    private InterfaceStatus(int value) {
        intValue = value;
    }

    public int getValue() {
        return intValue;
    }

    public static InterfaceStatus forValue(int value) {
        return mappings.get(value);
    }
}
