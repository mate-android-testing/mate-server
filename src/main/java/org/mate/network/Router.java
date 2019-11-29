package org.mate.network;

import java.util.Map;
import java.util.TreeMap;

public class Router {
    private final TreeMap<String, Endpoint> routes;

    public Router() {
        routes = new TreeMap<>((s1, s2) -> {
            int compare = Integer.compare(s2.length(), s1.length());
            if (compare == 0) {
                return s1.compareTo(s2);
            }
            return compare;
        });
    }

    public void add(String path, Endpoint endpoint) {
        routes.put(path, endpoint);
    }

    public Endpoint resolve(String path) {
        for (Map.Entry<String, Endpoint> route : routes.entrySet()) {
            if (path.startsWith(route.getKey())) {
                return route.getValue();
            }
        }
        return null;
    }
}
