package org.mate.endpoints;

import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTree;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTreeEdge;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.Tuple;
import org.apache.commons.io.FileUtils;
import org.jf.dexlib2.analysis.AnalyzedInstruction;
import org.jgrapht.GraphPath;
import org.mate.crash_reproduction.AtStackTraceLine;
import org.mate.crash_reproduction.BranchLocator;
import org.mate.crash_reproduction.StackTrace;
import org.mate.crash_reproduction.StackTraceParser;
import org.mate.graphs.*;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This endpoint offers an interface to operate with graphs in the background.
 * This can be a simple control flow graph to evaluate branch distance, but also
 * a system dependence graph. The usage of this endpoint requires the
 * android-graphs-lib.jar as a dependency.
 */
public class GraphEndpoint implements Endpoint {

    private final AndroidEnvironment androidEnvironment;
    private Graph graph;
    private CallTree callTree;
    private ActivityGraph activityGraph;
    private final Path appsDir;
    private final Path resultsPath;

    // a target vertex (a random branch)
    private List<Vertex> targetVertexes;
    private Map<AtStackTraceLine, Set<Vertex>> targetVertices;
    private Map<AtStackTraceLine, Tuple<IntraCFG, Set<Vertex>>> targetMethodGraphs;
    private Set<String> targetComponents;
    private StackTrace stackTrace;
    private BranchLocator branchLocator;

    public GraphEndpoint(AndroidEnvironment androidEnvironment, Path appsDir, Path resultsPath) {
        this.androidEnvironment = androidEnvironment;
        this.appsDir = appsDir;
        this.resultsPath = resultsPath;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/graph/init")) {
            return initGraph(request);
        } else if (request.getSubject().startsWith("/graph/activity_graph_init")) {
            return activityGraphInit(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance_vector")) {
            return getBranchDistanceVector(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
        } else if (request.getSubject().startsWith("/graph/draw")) {
            return drawGraph(request);
        } else if (request.getSubject().startsWith("/graph/callTree/draw")) {
            return drawCallTree(request);
        } else if (request.getSubject().startsWith("/graph/get_target_activities")) {
            return new Message.MessageBuilder("/graph/get_target_activities")
                    .withParameter("target_activities", String.join(",", targetComponents))
                    .build();
        } else if (request.getSubject().startsWith("/graph/reached_targets")) {
            return reachedTargets(request);
        } else if (request.getSubject().startsWith("/graph/get_activity_distance")) {
            return getActivityDistance(request);
        }  else if (request.getSubject().startsWith("/graph/get_all_activity_distances")) {
            return getAllActivityDistances(request);
        } else if (request.getSubject().startsWith("/graph/get_max_activity_distance")) {
            return getMaxActivityDistance(request);
        } else if (request.getSubject().startsWith("/graph/stack_trace_tokens")) {
            String packageName = request.getParameter("package");
            Set<String> stackTraceTokens = stackTrace.getFuzzyTokens(packageName);
            Stream<String> instructionTokens = branchLocator.getTokensForStackTrace(stackTrace, packageName);
            Stream<String> tokens = Stream.concat(stackTraceTokens.stream(), instructionTokens);
            return new Message.MessageBuilder("/graph/stack_trace_tokens")
                    .withParameter("tokens", String.join(",", tokens.collect(Collectors.toSet())))
                    .build();
        } else if(request.getSubject().startsWith("/graph/stack_trace_user_tokens")) {
            return new Message.MessageBuilder("/graph/stack_trace_user_tokens")
                    .withParameter("tokens", String.join(",", stackTrace.getUserTokens()))
                    .build();
        } else if (request.getSubject().startsWith("/graph/stack_trace")) {
            return new Message.MessageBuilder("/graph/stack_trace")
                    .withParameter("stack_trace", String.join(",", stackTrace.getAtLines()))
                    .build();
        } else if (request.getSubject().startsWith("/graph/call_tree_distance")) {
            return getNormalizedCallTreeDistance(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndpoint!");
        }
    }

    private Set<String> getTraces(Message request) {
        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        // get list of traces file
        Path appDir = appsDir.resolve(packageName);
        File tracesDir = appDir.resolve("traces").toFile();

        // collect the relevant traces files
        // read traces from trace file(s)
        return readTraces(getTraceFiles(tracesDir, chromosome));
    }

    private Set<String> getVisitedMethods(Message request) {
        return getTraces(request).stream().map(this::traceToMethod).collect(Collectors.toSet());
    }

    private Message getNormalizedCallTreeDistance(Message request) {
        String chromosome = request.getParameter("chromosome");
        double minDistance = getCallTreeDistance(request);
        double normalizedDistance = minDistance == Integer.MAX_VALUE
                ? 1
                : minDistance / (minDistance + 1);

        if (normalizedDistance < 0 || normalizedDistance > 1) {
            throw new IllegalStateException();
        }

        Log.println("CallTree distance for " + chromosome + " is: abs. distance " + minDistance + ", rel. distance " + normalizedDistance);

        return new Message.MessageBuilder("/graph/call_tree_distance")
                .withParameter("distance", "" + normalizedDistance)
                .build();
    }

    private int getCallTreeDistance(Message request) {
        String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        Set<String> traces = getVisitedMethods(request);
        List<CallTreeVertex> callTreeVertices = targetVertexes.stream().map(Vertex::getMethod).map(CallTreeVertex::new).collect(Collectors.toList());
        Collections.reverse(callTreeVertices);

        Optional<CallTreeVertex> lastCoveredVertex = Optional.empty();
        while (!callTreeVertices.isEmpty() && traces.contains(callTreeVertices.get(0).getMethod())) {
            // Remove target vertices that we have already covered
            lastCoveredVertex = Optional.of(callTreeVertices.remove(0));
        }

        if (callTreeVertices.isEmpty()) {
            // We have already reached all targets
            return 0;
        } else if (lastCoveredVertex.isPresent()) {
            // There are some targets left, and we have already covered some
            // -> we need to find the path between the last target found and the remaining ones
            return callTree.getShortestPathWithStops(lastCoveredVertex.get(), callTreeVertices).orElseThrow().getLength();
        } else {
            // We have not found any targets yet
            int minDistance = Integer.MAX_VALUE;
            Optional<GraphPath<CallTreeVertex, CallTreeEdge>> minPath = Optional.empty();

            for (String trace : traces) {
                var path = callTree.getShortestPathWithStops(new CallTreeVertex(trace), callTreeVertices);
                if (path.isPresent()) {
                    int distance = path.get().getLength();

                    if (distance < minDistance) {
                        minPath = path;
                        minDistance = distance;
                    }
                }
            }
            return minDistance;
        }
    }

    private Message reachedTargets(Message request) {
        Set<String> traces = getTraces(request);
        // If an exception is thrown then there is no trace of the line that threw the exception
        // Since (at least one of) the target lines will throw an exception we need to manually add these traces
        // in order to decide whether we have reached all target lines/methods
        traces.addAll(deduceTracesFromStackTrace(request.getParameter("stackTrace"), request.getParameter("packageName")));

        Message response = new Message(request.getSubject());

        appendMap(response, "reachedTargetComponents", reachedTargetComponents(traces), Function.identity(), Object::toString);
        appendMap(response, "reachedTargetMethods", reachedTargetMethods(traces), AtStackTraceLine::toString, Object::toString);
        appendMap(response, "reachedTargetLines", reachedTargetLines(traces), AtStackTraceLine::toString, Object::toString);

        return response;
    }

    private Set<String> deduceTracesFromStackTrace(String stackTrace, String packageName) {
        if (stackTrace == null) {
            return Set.of();
        } else {
            StackTrace stackTraceObj = StackTraceParser.parse(Arrays.asList(stackTrace.split("\n")));
            return branchLocator.getTargetTracesForStackTrace(stackTraceObj.getStackTraceAtLines().collect(Collectors.toList()), (InterCFG) graph, packageName)
                    .values().stream().flatMap(Collection::stream)
                    // TODO This is an over approximation, since it will add traces that came potentially after the crash
                    // (e.g. if the exception is thrown at the beginning of a block statement we will still pretend like the remaining statements
                    // from the block statement were reached as well)
                    .flatMap(v -> tracesForStatement(v.getStatement()))
                    .collect(Collectors.toSet());
        }
    }

    private static <K, V> void appendMap(Message request, String mapName, Map<K, V> map, Function<K, String> keyToString, Function<V, String> valueToString) {
        request.addParameter(mapName + ".size", String.valueOf(map.size()));

        int pos = 0;
        for (var entry : map.entrySet()) {
            request.addParameter(mapName + ".k" + pos, keyToString.apply(entry.getKey()));
            request.addParameter(mapName + ".v" + pos, valueToString.apply(entry.getValue()));
            pos++;
        }
    }

    private Map<String, Boolean> reachedTargetComponents(Set<String> traces) {
        Set<String> reachedClasses = traces.stream().map(this::traceToClass).collect(Collectors.toSet());
        return targetComponents.stream()
                .map(ClassUtils::convertDottedClassName)
                .collect(Collectors.toMap(Function.identity(), reachedClasses::contains));
    }

    private Map<AtStackTraceLine, Boolean> reachedTargetMethods(Set<String> traces) {
        Set<String> reachedMethods = traces.stream().map(this::traceToMethod).collect(Collectors.toSet());
        Map<AtStackTraceLine, Boolean> map = targetVertices.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    String method = expectOne(e.getValue().stream().map(Vertex::getMethod).collect(Collectors.toSet()));

                    return reachedMethods.contains(method);
                }));
        onlyAllowCoveredIfPredecessorCoveredAsWell(map);
        return map;
    }

    private Map<AtStackTraceLine, Boolean> reachedTargetLines(Set<String> traces) {
        Set<String> tracesWithoutCoverageInfo = traces.stream()
                .map(trace -> {
                    String[] parts = trace.split("->");

                    return parts[0] + "->" + parts[1] + "->" + parts[2];
                }).collect(Collectors.toSet());
        Map<AtStackTraceLine, Boolean> map = targetVertices.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    long matches = e.getValue().stream()
                            .filter(v -> tracesForStatement(v.getStatement()).anyMatch(tracesWithoutCoverageInfo::contains))
                            .count();

                    if (matches == 0) {
                        return false;
                    } else if (matches == e.getValue().size()) {
                        return true;
                    } else {
                        Log.printWarning("Reached only some parts of line?");
                        return true;
                    }
                }));
        onlyAllowCoveredIfPredecessorCoveredAsWell(map);
        return map;
    }

    private void onlyAllowCoveredIfPredecessorCoveredAsWell(Map<AtStackTraceLine, Boolean> map) {
        // We are only interested in a covered method if its predecessor from the stack trace was reached as well
        // TODO Does not consider the following case:
        // Stack trace from crash we are trying to reproduce:
        // at com.example.Class2.method2()
        // at com.example.Class1.method1()
        //
        // Traces
        // - com.example.Class2.method2() covered
        // - com.example.Class1.method1() covered
        //
        // Result
        // - com.example.Class1.method1() will be marked as reached -> fine
        // - com.example.Class2.method2() will be marked as reached
        //      -> Case: method2 is called by method3
        //      -> should ideally not be marked as reached (since it was not called by method1)

        var orderedEntries = stackTrace.getStackTraceAtLines()
                .filter(map::containsKey)
                .map(line -> map.entrySet().stream().filter(e -> e.getKey().equals(line)).findAny())
                .map(Optional::orElseThrow)
                .collect(Collectors.toList());
        Collections.reverse(orderedEntries);

        Iterator<Map.Entry<AtStackTraceLine, Boolean>> coveredStackTraceLineIterator = orderedEntries.listIterator();

        while (coveredStackTraceLineIterator.hasNext() && coveredStackTraceLineIterator.next().getValue()) {
            // Run from bottom to top of stack trace lines until an uncovered line is reached
        }

        // Set remaining lines to not covered, since predecessor is also not covered
        while (coveredStackTraceLineIterator.hasNext()) {
            coveredStackTraceLineIterator.next().setValue(false);
        }
    }

    private static <T> T expectOne(Collection<T> collection) {
        if (collection.isEmpty()) {
            throw new NoSuchElementException();
        } else if (collection.size() > 1) {
            throw new UnsupportedOperationException();
        } else {
            return collection.stream().findAny().orElseThrow();
        }
    }

    private Stream<String> tracesForStatement(Statement statement) {
        return getInstructions(statement)
                .map(i -> statement.getMethod() + "->" + i.getInstructionIndex());
    }

    private static Stream<AnalyzedInstruction> getInstructions(Statement statement) {
        if (statement instanceof BasicStatement) {
            return Stream.of(((BasicStatement) statement).getInstruction());
        } else if (statement instanceof BlockStatement) {
            return ((BlockStatement) statement).getStatements()
                    .stream().flatMap(GraphEndpoint::getInstructions);
        } else {
            return Stream.empty();
        }
    }

    private String traceToMethod(String method) {
        String[] parts = method.split("->");
        return parts[0] + "->" + parts[1];
    }

    private String traceToClass(String trace) {
        return trace.split("->")[0];
    }

    private Set<String> getTargetComponents(String packageName, ComponentType... componentType) {
        return ((InterCFG) graph).getTargetComponents(stackTrace.getStackTraceAtLines().filter(l -> l.isFromPackage(packageName)).collect(Collectors.toList()), componentType);
    }

    private Message getActivityDistance(Message request) {
        Set<String> targetActivities = new HashSet<>(Arrays.asList(request.getParameter("targetActivities").split(",")));
        List<String> activitySequence = Arrays.asList(request.getParameter("activitySequence").split(","));

        // Calculate average distance
        return new Message.MessageBuilder("/graph/get_activity_distance")
                .withParameter("activity_distance", String.valueOf(activitySequence.stream().mapToInt(activity -> getActivityDistance(targetActivities, activity)).average().orElseThrow()))
                .build();
    }

    private int getActivityDistance(Set<String> targetActivities, String activity) {
        return targetActivities.stream().mapToInt(targetActivity -> activityGraph.getMinDistance(activity, targetActivity)).min().orElseThrow();
    }

    private Message getMaxActivityDistance(Message request) {
        Set<String> targetActivities = new HashSet<>(Arrays.asList(request.getParameter("targetActivities").split(",")));
        int maxActivityDistance = targetActivities.stream().mapToInt(activityGraph::getMaxDistance).max().orElseThrow();
        Log.println("Max activity distance is: " + maxActivityDistance);

        return new Message.MessageBuilder("/graph/get_max_activity_distance")
                .withParameter("max_activity_distance", String.valueOf(maxActivityDistance))
                .build();
    }

    private Message getAllActivityDistances(Message request) {
        Set<String> targetActivities = new HashSet<>(Arrays.asList(request.getParameter("targetActivities").split(",")));

        return new Message.MessageBuilder("/graph/get_max_activity_distance")
                .withParameter("activity_distances", activityGraph.getMinDistances(targetActivities).entrySet().stream()
                        .map(entry -> entry.getKey() + ":" + entry.getValue())
                        .collect(Collectors.joining(";")))
                .build();
    }

    private Message drawGraph(Message request) {

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        boolean raw = Boolean.parseBoolean(request.getParameter("raw"));

        File appDir = new File(appsDir.toFile(), graph.getAppName());
        File drawDir = new File(appDir, "graph-drawings");
        drawDir.mkdirs();

        if (raw) {
            Log.println("Drawing raw graph...");
            graph.draw(drawDir);
        } else {
            Log.println("Drawing graph...");

            // determine the target vertices (e.g. single branch or all branches)

            Set<Vertex> targetVertices = new HashSet<>(Objects.requireNonNullElseGet(targetVertexes, () -> new HashSet<>(graph.getBranchVertices())));

            // retrieve the visited vertices
            String chromosome = request.getParameter("chromosome");
            Log.println("Drawing graph with visited vertices from chromosome: " + chromosome);
            Set<Vertex> visitedVertices = getVisitedVertices(appDir, chromosome);

            // draw the graph where target and visited vertices are marked in different colours
            graph.draw(drawDir, visitedVertices, targetVertices);
        }

        return new Message("/graph/draw");
    }

    private Message drawCallTree(Message message) {
        Set<String> visitedMethods = getVisitedMethods(message);
        Map<String, String> highlightMethods = visitedMethods.stream()
                .collect(Collectors.toMap(Function.identity(), a -> "red"));
        targetVertexes.stream().map(Vertex::getMethod).forEach(target -> highlightMethods.put(target, "blue"));

        String id = message.getParameter("id");

        File file = resultsPath.resolve(id + ".dot").toFile();
        callTree.toDot(file, highlightMethods);

        return new Message("/graph/callTree/draw");
    }

    private Set<Vertex> getVisitedVertices(File appDir, String chromosomes) {

        // get list of traces file
        File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // read traces from trace file(s)
        Set<String> traces = readTraces(tracesFiles);

        return mapTracesToVertices(traces);
    }

    /**
     * Selects a target vertex based on the given target criteria.
     *
     * @param target Describes how a target should be selected.
     * @return Returns the selected target vertex.
     */
    private List<Vertex> selectTargetVertex(String target, String packageName, File apkPath) {

        Log.println("Target vertex selection strategy: " + target);

        switch (target) {
            case "no_target":
                return null;
            case "random_target":
            case "random_branch":
                List<Vertex> targets = target.equals("random_target") ? graph.getVertices() : graph.getBranchVertices();

                while (true) {
                    Random rand = new Random();
                    Vertex randomVertex = targets.get(rand.nextInt(targets.size()));

                    if (graph.isReachable(randomVertex)) {
                        Log.println("Randomly selected target vertex: " + randomVertex + " [" + randomVertex.getMethod() + "]");
                        return List.of(randomVertex);
                    }
                }
            case "stack_trace":
                File appDir = new File(appsDir.toFile(), packageName);

                // the stack_trace.txt should be located within the app directory
                File stackTraceFile = new File(appDir, "stack_trace.txt");

                try {
                    branchLocator = new BranchLocator(apkPath);

                    stackTrace = StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()));
                    targetVertices = branchLocator.getTargetTracesForStackTrace(stackTrace.getStackTraceAtLines().collect(Collectors.toList()), (InterCFG) graph, packageName);
                    targetComponents = getTargetComponents(packageName, ComponentType.ACTIVITY, ComponentType.FRAGMENT);
                    targetMethodGraphs = targetVertices.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                                String method = expectOne(e.getValue().stream().map(Vertex::getMethod).collect(Collectors.toSet()));
                                var intra = new IntraCFG(apkPath, method, true, appsDir, packageName);
                                Set<Vertex> intraVertices = targetVertices.get(e.getKey()).stream()
                                        .flatMap(interVertex -> tracesForStatement(interVertex.getStatement()))
                                        .map(intra::lookupVertex)
                                        .collect(Collectors.toSet());
                                return new Tuple<>(intra, intraVertices);
                            }));

                    List<Vertex> targetVertex = stackTrace.getStackTraceAtLines()
                            .filter(targetVertices::containsKey)
                            .map(targetVertices::get)
                            .flatMap(Collection::stream)
                            .collect(Collectors.toList());

                    if (targetVertex.isEmpty()) {
                        throw new UnsupportedOperationException("To targets found for stack trace: " + target);
                    }
                    return targetVertex;
                } catch (IOException e) {
                    Log.printError("Could not read stack trace file from '" + stackTraceFile.getAbsolutePath() + "'!");
                    throw new UncheckedIOException(e);
                }
            default:
                List<Vertex> targetVertex = Arrays.stream(target.split(",")).map(graph::lookupVertex).collect(Collectors.toList());

                if (targetVertex.isEmpty()) {
                    throw new UnsupportedOperationException("Custom target vertex not found: " + target);
                }
                return targetVertex;
        }
    }

    private Message activityGraphInit(Message request) {
        String packageName = request.getParameter("packageName");

        File activityGraphMap = appsDir.resolve(packageName).resolve("activity-graph.txt").toFile();

        if (!activityGraphMap.exists()) {
            throw new IllegalArgumentException("Can't locate activity graph " + activityGraphMap.getAbsolutePath());
        }
        activityGraph = new ActivityGraph(activityGraphMap);

        return new Message("/graph/activity_graph_init");
    }

    private Message initGraph(Message request) {

        String packageName = request.getParameter("packageName");
        GraphType graphType = GraphType.valueOf(request.getParameter("graph_type"));
        File apkPath = new File(request.getParameter("apk"));
        String target = request.getParameter("target");

        if (!apkPath.exists()) {
            throw new IllegalArgumentException("Can't locate APK: " + apkPath.getAbsolutePath() + "!");
        }

        boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));

        switch (graphType) {
            case INTRA_CFG:
                String methodName = request.getParameter("method");
                return initIntraCFG(apkPath, methodName, useBasicBlocks, packageName, target);
            case INTER_CFG:
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                return initInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses,
                        packageName, target);
            default:
                throw new UnsupportedOperationException("Graph type not yet supported!");
        }
    }

    private Message initIntraCFG(File apkPath, String methodName, boolean useBasicBlocks,
                                 String packageName, String target) {
        graph = new IntraCFG(apkPath, methodName, useBasicBlocks, appsDir, packageName);
        targetVertexes = selectTargetVertex(target, packageName, apkPath);
        return new Message("/graph/init");
    }

    private Message initInterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                                 boolean resolveOnlyAUTClasses, String packageName, String target) {
        var g = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        callTree = g.getCallTree();
        graph = g;
        targetVertexes = selectTargetVertex(target, packageName, apkPath);
        return new Message("/graph/init");
    }

    /**
     * Computes the fitness value for a given chromosome combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance information.
     */
    private Message getBranchDistance(Message request) {

        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        // get list of traces file
        Path appDir = appsDir.resolve(packageName);
        File tracesDir = appDir.resolve("traces").toFile();

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosome);

        // read traces from trace file(s)
        Set<String> traces = readTraces(tracesFiles);

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = mapTracesToVertices(traces);

        Log.println("Looking at " + chromosome);
        Log.println(visitedVertices.stream().map(Vertex::toString).collect(Collectors.joining("\n")));
        double sum = IntStream.range(0, targetVertexes.size()).parallel().mapToDouble(index -> {
            double branchDistance = getBranchDistance(visitedVertices, traces, targetVertexes.get(index));
            Log.println("Branchdistance is: " + branchDistance + " for index " + index + " is: " + branchDistance * (index + 1));
            return branchDistance * (index + 1);
        }).sum();
        Log.println("Total branchDistance is " + sum);
        int n = targetVertexes.size();
        int sumUp = (n * (n+1)) / 2;
        double avgBranchDistance = sum / sumUp;
        Log.println("Avg branchdistance is " + avgBranchDistance + " (n=" + n + "sumUp=" + sumUp + ")");

        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", String.valueOf(avgBranchDistance))
                .build();
    }

    private double getBranchDistance(Set<Vertex> visitedVertices, Set<String> traces, Vertex targetVertex) {
        long start = System.currentTimeMillis();

        // the minimal distance between a execution path and a chosen target vertex
        AtomicInteger minDistance = new AtomicInteger(Integer.MAX_VALUE);

        // save the closest vertex -> required for approach level
        AtomicReference<Vertex> minDistanceVertex = new AtomicReference<>();

        // track global minimum distance + vertex (solely for debugging)
        AtomicInteger minDistanceGlobal = new AtomicInteger(Integer.MAX_VALUE);
        AtomicReference<Vertex> minDistanceVertexGlobal = new AtomicReference<>();

        visitedVertices.parallelStream().forEach(visitedVertex -> {

            int distance = graph.getDistance(visitedVertex, targetVertex);

            synchronized (this) {
                if (distance < minDistance.get() && distance != -1) {
                    /*
                     * We are only interested in a direct hit (covered branch) or the distance to an if statement.
                     * This equals distances of either if statements or branches and excludes distances to visited
                     * entry or exit vertices.
                     */
                    if ((distance == 0) || visitedVertex.isIfVertex()) {
                        minDistanceVertex.set(visitedVertex);
                        minDistance.set(distance);
                    }
                }

                if (distance < minDistanceGlobal.get() && distance != -1) {
                    // found global shorter path, e.g. distance to a visited entry or exit vertex
                    minDistanceGlobal.set(distance);
                    minDistanceVertexGlobal.set(visitedVertex);
                }
            }
        });

        Log.println("Shortest path length: " + minDistance.get());
        Log.println("Shortest path length (global): " + minDistanceGlobal.get());

        if (minDistanceVertexGlobal.get() != null) {
            Log.println("Closest global vertex: " + minDistanceVertexGlobal.get().getMethod()
                    + "->[ " + minDistanceVertexGlobal.get().getStatement() + "]");
        }

        long end = System.currentTimeMillis();
        Log.println("Computing approach level took: " + (end - start) + " ms.");

        if (minDistance.get() == Integer.MAX_VALUE) {
            // branch not reachable by execution path
            return 1;
        } else if (minDistance.get() == 0) {
            // covered target branch
            return 0;
        } else {
            // combine approach level + branch distance
            int approachLevel = minDistance.get();

            /*
             * The vertex with the closest distance represents an if stmt, at which the execution path took the wrong
             * direction. We need to find the shortest branch distance value for the given if stmt. Note that the if
             * stmt could have been visited multiple times.
             */
            Vertex ifVertex = minDistanceVertex.get();
            Statement stmt = ifVertex.getStatement();

            Log.println("Closest if vertex: " + ifVertex.getMethod() + "[" + ifVertex.getStatement() + "]");

            // we only support basic blocks right now
            assert stmt.getType() == Statement.StatementType.BLOCK_STATEMENT;

            // the if stmt is located the last position of the block
            BasicStatement ifStmt = (BasicStatement) ((BlockStatement) stmt).getLastStatement();

            // find the branch distance trace(s) that describes the if stmt
            String prefix = ifVertex.getMethod() + "->" + ifStmt.getInstructionIndex() + ":";

            Log.println("Trace describing closest if stmt: " + prefix);

            double minBranchDistance = Double.MAX_VALUE;

            /*
             * We need to look for branch distance traces that refer to the if statement. A branch distance trace is
             * produced for both branches, but we only need to consider those traces that describe the branch that
             * couldn't be covered, otherwise we would have actually taken the right branch. Thus, the relevant distance
             * traces (we may have visited the if statement multiple times) must contain a distance > 0, since a branch
             * with a distance of 0 would have be taken. We simply need to pick the minimum of those distance traces.
             */
            for (String trace : traces) {
                if (trace.startsWith(prefix)) {

                    // found a branch distance value for the given if stmt
                    double distance = Double.parseDouble(trace.split(":")[1]);

                    // we need to track the minimal distance > 0 (non-covered branch)
                    if (distance > 0 && distance < minBranchDistance) {
                        minBranchDistance = distance;
                    }
                }
            }

            Log.println("Approach level: " + approachLevel);
            Log.println("Minimal branch distance: " + minBranchDistance);

            // combine and normalise
            // 1 / (1 + 1) = 0.5
            double normalisedBranchDistance = minBranchDistance / (minBranchDistance + 1);
            // 0
            double combined = approachLevel + normalisedBranchDistance;
            combined = combined / (combined + 1);
            return combined;
        }
    }

    /**
     * Computes the fitness value vector for a given chromosome combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance fitness vector.
     */
    private Message getBranchDistanceVector(Message request) {

        String packageName = request.getParameter("packageName");
        String chromosome = request.getParameter("chromosome");

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        Log.println("Computing the branch distance vector for the chromosome: " + chromosome);

        // get list of traces file
        Path appDir = appsDir.resolve(packageName);
        File tracesDir = appDir.resolve("traces").toFile();

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, chromosome);

        // read the traces from the traces files
        Set<String> traces = readTraces(tracesFiles);

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = mapTracesToVertices(traces);

        // evaluate fitness value (approach level + branch distance) for each single branch
        List<String> branchDistanceVector = new LinkedList<>();

        for (Vertex branch : graph.getBranchVertices()) {

            Log.println("Target branch vertex: " + branch.getMethod() + "->["
                        + branch.getStatement() + "]");

            // find the shortest distance (approach level) to the given branch
            AtomicInteger minDistance = new AtomicInteger(Integer.MAX_VALUE);

            // save the closest vertex -> required for approach level
            AtomicReference<Vertex> minDistanceVertex = new AtomicReference<>();

            // track global minimum distance + vertex (solely for debugging)
            AtomicInteger minDistanceGlobal = new AtomicInteger(Integer.MAX_VALUE);
            AtomicReference<Vertex> minDistanceVertexGlobal = new AtomicReference<>();

            visitedVertices.parallelStream().forEach(visitedVertex -> {

                int distance = graph.getDistance(visitedVertex, branch);

                synchronized (this) {
                    if (distance < minDistance.get() && distance != -1) {
                        /*
                         * We are only interested in a direct hit (covered branch) or the distance to an if statement.
                         * This equals distances of either if statements or branches and excludes
                         * distances to visited entry or exit vertices.
                         */
                        if ((distance == 0 && visitedVertex.isBranchVertex()) || visitedVertex.isIfVertex()) {
                            minDistanceVertex.set(visitedVertex);
                            minDistance.set(distance);
                        }
                    }

                    if (distance < minDistanceGlobal.get() && distance != -1) {
                        // found global shorter path, e.g. distance to a visited entry or exit vertex
                        minDistanceGlobal.set(distance);
                        minDistanceVertexGlobal.set(visitedVertex);
                    }
                }
            });

            Log.println("Shortest path length: " + minDistance.get());
            Log.println("Shortest path length (global): " + minDistanceGlobal.get());

            if (minDistanceVertexGlobal.get() != null) {
                Log.println("Closest global vertex: " + minDistanceVertexGlobal.get().getMethod()
                        + "->[ " + minDistanceVertexGlobal.get().getStatement() + "]");
            }

            if (minDistance.get() == Integer.MAX_VALUE) {
                // branch not reachable by execution path
                branchDistanceVector.add(String.valueOf(1));
            } else if (minDistance.get() == 0) {
                // covered target branch
                branchDistanceVector.add(String.valueOf(0));
            } else {
                // combine approach level + branch distance
                int approachLevel = minDistance.get();

                /*
                 * The vertex with the closest distance represents an if stmt, at which the execution path took the wrong
                 * direction. We need to find the shortest branch distance value for the given if stmt. Note that the
                 * if stmt could have been visited multiple times.
                 */
                Vertex ifVertex = minDistanceVertex.get();
                Statement stmt = ifVertex.getStatement();

                Log.println("Closest if vertex: " + ifVertex.getMethod() + "[" + ifVertex.getStatement() + "]");

                // we only support basic blocks right now
                assert stmt.getType() == Statement.StatementType.BLOCK_STATEMENT;

                // the if stmt is located the last position of the block
                BasicStatement ifStmt = (BasicStatement) ((BlockStatement) stmt).getLastStatement();

                // find the branch distance trace(s) that describes the if stmt
                String prefix = ifVertex.getMethod() + "->" + ifStmt.getInstructionIndex() + ":";

                Log.println("Trace describing closest if stmt: " + prefix);

                double minBranchDistance = Double.MAX_VALUE;

                /*
                 * We need to look for branch distance traces that refer to the if statement. A branch distance trace is
                 * produced for both branches, but we only need to consider those traces that describe the branch that
                 * couldn't be covered, otherwise we would have actually taken the right branch. Thus, the relevant
                 * distance traces (we may have visited the if statement multiple times) must contain a distance > 0,
                 * since a branch with a distance of 0 would have be taken. We simply need to pick the minimum of those
                 * distance traces.
                 */
                for (String trace : traces) {
                    if (trace.startsWith(prefix)) {

                        // found a branch distance value for the given if stmt
                        double distance = Double.parseDouble(trace.split(":")[1]);

                        // we need to track the minimal distance > 0 (non-covered branch)
                        if (distance > 0 && distance < minBranchDistance) {
                            minBranchDistance = distance;
                        }
                    }
                }

                Log.println("Approach level: " + approachLevel);
                Log.println("Minimal branch distance: " + minBranchDistance);

                // combine and normalise
                double normalisedBranchDistance = minBranchDistance / (minBranchDistance + 1);
                double combined = approachLevel + normalisedBranchDistance;
                combined = combined / (combined + 1);
                branchDistanceVector.add(String.valueOf(combined));
            }
        }

        Log.println("Branch Distance Vector: " + branchDistanceVector);

        return new Message.MessageBuilder("/graph/get_branch_distance_vector")
                .withParameter("branch_distance_vector", String.join("+", branchDistanceVector))
                .build();
    }

    /**
     * Gets the list of traces files specified by the given chromosomes.
     *
     * @param tracesDir   The base directory containing the traces files.
     * @param chromosomes Encodes a mapping to one or several traces files.
     * @return Returns the list of traces files described by the given chromosomes.
     */
    private List<File> getTraceFiles(File tracesDir, String chromosomes) {

        // collect the relevant traces files
        List<File> tracesFiles = new ArrayList<>(FileUtils.listFiles(tracesDir, null, true));

        if (chromosomes != null) {

            // only consider the traces files described by the chromosome ids
            tracesFiles = new ArrayList<>();

            for (String chromosome : chromosomes.split("\\+")) {
                try {
                    tracesFiles.addAll(
                            Files.walk(tracesDir.toPath().resolve(chromosome))
                                    .filter(Files::isRegularFile)
                                    .map(Path::toFile)
                                    .collect(Collectors.toList()));
                } catch (IOException e) {
                    Log.printError("Couldn't retrieve traces files!");
                    throw new IllegalArgumentException(e);
                }
            }
        }

        Log.println("Number of considered traces files: " + tracesFiles.size());
        return tracesFiles;
    }

    /**
     * Reads the traces from the given list of traces files.
     *
     * @param tracesFiles A list of traces files.
     * @return Returns the unique traces contained in the given traces files.
     */
    private Set<String> readTraces(List<File> tracesFiles) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        Set<String> traces = new HashSet<>();

        for (File traceFile : tracesFiles) {
            try (Stream<String> stream = Files.lines(traceFile.toPath(), StandardCharsets.UTF_8)) {
                traces.addAll(stream.collect(Collectors.toList()));
            } catch (IOException e) {
                Log.println("Reading traces.txt failed!");
                throw new IllegalStateException(e);
            }
        }

        long end = System.currentTimeMillis();
        Log.println("Reading traces from file(s) took: " + (end - start) + " ms.");

        Log.println("Number of collected traces: " + traces.size());
        return traces;
    }

    /**
     * Maps the given set of traces to vertices in the graph.
     *
     * @param traces The set of traces that should be mapped to vertices.
     * @return Returns the vertices described by the given set of traces.
     */
    private Set<Vertex> mapTracesToVertices(Set<String> traces) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        Set<Vertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());

        // map trace to vertex
        traces.parallelStream().forEach(trace -> {

            if (trace.contains(":")) {
                // skip branch distance trace
                return;
            }

            // mark virtual entry/exit vertices
            if (trace.contains("->entry")) {

                String entryTrace = trace.split("->entry")[0] + "->entry";
                Vertex visitedEntry = graph.lookupVertex(entryTrace);

                if (visitedEntry == null) {
                    Log.printWarning("Couldn't derive vertex for entry trace: " + entryTrace);
                } else {
                    visitedVertices.add(visitedEntry);
                }
            } else if (trace.contains("->exit")) {

                String exitTrace = trace.split("->exit")[0] + "->exit";
                Vertex visitedExit = graph.lookupVertex(exitTrace);

                if (visitedExit == null) {
                    Log.printWarning("Couldn't derive vertex for exit trace: " + exitTrace);
                } else {
                    visitedVertices.add(visitedExit);
                }
            }

            // mark actual vertex corresponding to trace
            Vertex visitedVertex = graph.lookupVertex(trace);

            if (visitedVertex == null) {
                Log.printWarning("Couldn't derive vertex for trace: " + trace);
            } else {
                visitedVertices.add(visitedVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping traces to vertices took: " + (end - start) + " ms.");

        Log.println("Number of visited vertices: " + visitedVertices.size());
        return visitedVertices;
    }
}
