package com.example.investment.util;

import org.json.JSONArray;
import org.json.JSONException;

public class JSONUtils {
    public static JSONArray mergeArrays(JSONArray oldArray, JSONArray newArray) throws JSONException {
        JSONArray merged = new JSONArray();
        // Add all elements from old array
        for (int i = 0; i < oldArray.length(); i++) {
            merged.put(oldArray.get(i));
        }
        // Add all elements from new array
        for (int i = 0; i < newArray.length(); i++) {
            merged.put(newArray.get(i));
        }
        return merged;
    }
}