package org.mate.graphs;

import org.apache.commons.lang3.tuple.Pair;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ActivityGraph {
    private final Map<String, Set<String>> edges;
    private final Map<Pair<String, String>, Integer> distancesCache = new HashMap<>();

    public ActivityGraph(File edgesFile) {
        try {
            this.edges = Files.lines(edgesFile.toPath()).collect(Collectors.toMap(
                    line -> line.split("->")[0],
                    line -> new HashSet<>(Arrays.asList(line.split("->")[1].split(",")))
            ));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ActivityGraph(Map<String, Set<String>> edges) {
        this.edges = edges;
    }

    public int getMinDistance(String from, String to) {
        int distance = distancesCache.computeIfAbsent(Pair.of(from, to),
                pair -> shortestPath(pair.getLeft(), pair.getRight()).map(List::size).orElse(edges.size()));
        Log.println(String.format("Distance %s -> %s = %d", from, to, distance));
        return distance;
    }

    private Optional<List<String>> shortestPath(String from, String to) {
        Deque<String> workQueue = new LinkedList<>();
        Set<String> explored = new HashSet<>();
        explored.add(from);
        workQueue.add(from);
        Map<String, String> predecessors = new HashMap<>();

        while (!workQueue.isEmpty()) {
            String v = workQueue.poll();

            if (to.equals(v)) {
                return Optional.of(shortestPath(from, v, predecessors));
            } else if (edges.containsKey(v)) {
                for (String target : edges.get(v)) {
                    if (!explored.contains(target)) {
                        explored.add(target);
                        workQueue.add(target);
                        predecessors.put(target, v);
                    }
                }
            }
        }

        return Optional.empty();
    }

    private List<String> shortestPath(String from, String to, Map<String, String> predecessorRelation) {
        List<String> transitions = new LinkedList<>();

        String state = to;
        while (!Objects.equals(state, from)) {
            String predecessor = predecessorRelation.get(state);

            transitions.add(predecessor);
            state = predecessor;
        }

        Collections.reverse(transitions);
        return transitions;
    }

    public int getMaxDistance(String to) {
        return edges.keySet()
                .stream().mapToInt(from -> getMinDistance(from, to))
                .max().orElseThrow();
    }

    public Map<String, Integer> getMinDistances(Set<String> toSet) {
        return edges.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), from -> toSet.stream().map(to -> getMinDistance(from, to)).min(Integer::compare).orElseThrow()));
    }
}
