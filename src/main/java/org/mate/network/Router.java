package org.mate.network;

import java.util.*;

public class Router {
    private final TreeMap<String, Endpoint> routes;

    public Router() {
        routes = new TreeMap<>(new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                return Integer.compare(s2.length(), s1.length());
            }
        });
    }

    public void add(String path, Endpoint endpoint) {
        routes.put(path, endpoint);
    }

    public Endpoint resolve(String path) {
        for (Map.Entry<String, Endpoint> route : routes.entrySet()) {
            if (route.getKey().equals(path)) {
                return route.getValue();
            }
        }
        return null;
    }
}
