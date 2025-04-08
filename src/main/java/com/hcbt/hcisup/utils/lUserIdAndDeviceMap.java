package com.hcbt.hcisup.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA
 *
 * @Author: Sout
 * @Date: 2024/11/16 下午12:04
 * @Description:
 */
public class lUserIdAndDeviceMap {

    private static final Map<String,String> lUserIdAndDeviceMap = new HashMap<>();

    public static void put(String key, String value) {
        lUserIdAndDeviceMap.put(key, value);
    }

    public static String get(String key) {
        return lUserIdAndDeviceMap.get(key);
    }

    public static void delete(String key) {
        lUserIdAndDeviceMap.remove(key);
    }

    public static List<String> getKeys() {
        return new ArrayList<>(lUserIdAndDeviceMap.keySet());
    }

}
