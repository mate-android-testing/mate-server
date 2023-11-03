package org.mate.endpoints;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.ReturnStatement;
import org.apache.commons.io.FileUtils;
import org.mate.crash_reproduction.StackTrace;
import org.mate.graphs.*;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This endpoint offers an interface to operate with graphs in the background. This can be a simple control flow graph
 * to evaluate branch distance, but also a system dependence graph. The usage of this endpoint requires the
 * android-graphs-lib.jar as a dependency.
 */
public class GraphEndpoint implements Endpoint {

    @SuppressWarnings("unused")
    private final AndroidEnvironment androidEnvironment;

    /**
     * The underlying graph.
     */
    private Graph graph = null;

    /**
     * The path to the apps directory.
     */
    private final Path appsDir;

    /**
     * The list of target vertices, e.g. all branches.
     */
    private List<? extends Vertex> targetVertices;

    public GraphEndpoint(AndroidEnvironment androidEnvironment, Path appsDir) {
        this.androidEnvironment = androidEnvironment;
        this.appsDir = appsDir;
    }

    @Override
    public Message handle(Message request) {
        if (request.getSubject().startsWith("/graph/init")) {
            return initGraph(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance_vector")) {
            return getBranchDistanceVector(request);
        } else if (request.getSubject().startsWith("/graph/get_branch_distance")) {
            return getBranchDistance(request);
        } else if (request.getSubject().startsWith("/graph/get_crash_distance")) {
            return getCrashDistance(request);
        } else if (request.getSubject().startsWith("/graph/draw")) {
            return drawGraph(request);
        } else if (request.getSubject().startsWith("/graph/stack_trace_tokens")) {
            return getStackTraceTokens(request);
        } else if(request.getSubject().startsWith("/graph/stack_trace_user_tokens")) {
            return getStackTraceUserTokens(request);
        } else if (request.getSubject().startsWith("/graph/stack_trace")) {
            return getStackTrace(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndpoint!");
        }
    }

    /**
     * Computes the approach level and branch distance for the given branch vertex (target) using the CFG.
     *
     * @param visitedVertices The list of visited vertices (traces).
     * @param branchVertex The given branch vertex (target).
     * @return Returns the combined approach level + branch distance for the given branch vertex.
     */
    private String computeApproachLevelAndBranchDistanceCFG(final List<CFGVertex> visitedVertices,
                                                            final CFGVertex branchVertex) {
        final InterCFG interCFG = (InterCFG) graph;
        return interCFG.computeApproachLevelAndBranchDistance(visitedVertices, branchVertex);
    }

    /**
     * Computes the approach level and branch distance for the given branch vertex (target) using the CDG.
     *
     * @param visitedVertices The list of visited vertices (traces).
     * @param branchVertex The given branch vertex (target).
     * @return Returns the combined approach level + branch distance for the given branch vertex.
     */
    private String computeApproachLevelAndBranchDistanceCDG(final Set<CFGVertex> visitedVertices,
                                                            final CFGVertex branchVertex, List<String> traces) {

        final CDG cdg = (CDG) graph;
        final int approachLevel;
        final double branchDistance;

        // If we covered the target branch, the approach level and branch distance is zero.
        if (visitedVertices.contains(branchVertex)) {
            approachLevel = 0;
            branchDistance = 0.0;
        } else {
            // Compute Approach Level
            Pair<CFGVertex, Integer> approachLevelPair = cdg.computeApproachLevel(branchVertex, visitedVertices);

            if (approachLevelPair.fst() == null) {
                // We haven't covered any control-dependent if or switch statement, thus there is no guidance from the
                // branch distance.
                approachLevel = approachLevelPair.snd();
                branchDistance = 1.0;
            } else {
                // This is the if or switch (actually case) statement from which an incorrect branch toward the target was taken.
                // Hence, we will use this vertex to compute the branch distance.
                final CFGVertex ifOrSwitchVertex = approachLevelPair.fst();
                approachLevel = approachLevelPair.snd();
                branchDistance = cdg.computeBranchDistance(ifOrSwitchVertex, traces);
            }
        }

        final double combined = approachLevel + branchDistance;
        final double combinedNormalized = combined / (combined + 1);
        return String.valueOf(combinedNormalized);
    }

    /**
     * Computes the fitness value for a given chromosome by combining approach level + branch distance and using the CDG.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance information.
     */
    private Message getBranchDistanceCDG(final Message request) {

        if (!(graph instanceof CDG)) {
            throw new UnsupportedOperationException("Approach Level & Branch Distance only defined on CDG so far!");
        }

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        final CDG cdg = (CDG) graph;

        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = new HashSet<>(cdg.lookupVertices(traces));
        final var branchDistance = computeApproachLevelAndBranchDistanceCDG(visitedVertices,
                // there is only a single target
                (CFGVertex) targetVertices.get(0), traces);
        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", branchDistance)
                .build();
    }

    /**
     * Computes the fitness value for a given chromosome by combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance information.
     */
    private Message getBranchDistance(final Message request) {

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        if (graph instanceof CDG) {
            return getBranchDistanceCDG(request);
        } else if (graph instanceof CFG) {
            return getBranchDistanceCFG(request);
        } else {
            throw new UnsupportedOperationException("Branch distance not defined on " + graph.getClass() + "!");
        }
    }

    /**
     * Computes the fitness value for a given chromosome by combining approach level + branch distance and using the CFG.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance information.
     */
    private Message getBranchDistanceCFG(final Message request) {

        if (!(graph instanceof InterCFG)) {
            throw new UnsupportedOperationException("Approach Level & Branch Distance only defined on InterCFG so far!");
        }

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        final InterCFG interCFG = (InterCFG) graph;

        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = interCFG.lookupVertices(traces);

        interCFG.precomputeBranchDistances(traces);
        final var branchDistance = interCFG.computeApproachLevelAndBranchDistance(visitedVertices,
                // there is only a single target
                (CFGVertex) targetVertices.get(0));
        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", branchDistance)
                .build();
    }

    /**
     * Computes the branch distance vector for a given chromosome by combining approach level + branch distance.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance vector.
     */
    private Message getBranchDistanceVector(final Message request) {

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        if (graph instanceof CDG) {
            return getBranchDistanceVectorCDG(request);
        } else if (graph instanceof CFG) {
            return getBranchDistanceVectorCFG(request);
        } else {
            throw new UnsupportedOperationException("Branch distance not defined on " + graph.getClass() + "!");
        }
    }

    /**
     * Computes the branch distance vector for a given chromosome by combining approach level + branch distance using the CFG.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance vector.
     */
    private Message getBranchDistanceVectorCFG(final Message request) {

        if (!(graph instanceof InterCFG)) {
            throw new UnsupportedOperationException("Approach Level & Branch Distance only defined on InterCFG so far!");
        }

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance vector for the chromosome: " + chromosome);

        InterCFG interCFG = (InterCFG) graph;

        long start = System.currentTimeMillis();
        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = interCFG.lookupVertices(traces);
        final var branchVertices =  interCFG.getBranchVertices();
        long start1 = System.currentTimeMillis();
        interCFG.precomputeBranchDistances(traces);
        long end1 = System.currentTimeMillis();
        Log.println("Pre-Computing branch distances took: " + (end1 - start1) + "ms");
        final List<String> branchDistanceVector = computeBranchDistanceVectorCFG(visitedVertices, branchVertices);
        long end = System.currentTimeMillis();
        Log.println("Computing branch distance vector took: " + (end - start) + "ms");

        return new Message.MessageBuilder("/graph/get_branch_distance_vector")
                .withParameter("branch_distance_vector", String.join("+", branchDistanceVector))
                .build();
    }

    /**
     * Computes the branch distance vector for a given chromosome
     * by combining approach level + branch distance using the CDG.
     *
     * @param request The request message.
     * @return Returns a message containing the branch distance vector.
     */
    private Message getBranchDistanceVectorCDG(final Message request) {

        if (!(graph instanceof CDG)) {
            throw new UnsupportedOperationException("Approach Level & Branch Distance only defined on CDG so far!");
        }

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance vector for the chromosome: " + chromosome);

        final CDG cdg = (CDG) graph;

        long start = System.currentTimeMillis();
        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = new HashSet<>(cdg.lookupVertices(traces));
        final var branchVertices =  cdg.getBranchVertices();

        final List<String> branchDistanceVector = computeBranchDistanceVectorCDG(visitedVertices, branchVertices, traces);
        long end = System.currentTimeMillis();
        Log.println("Computing branch distance vector took: " + (end - start) + "ms");

        return new Message.MessageBuilder("/graph/get_branch_distance_vector")
                .withParameter("branch_distance_vector", String.join("+", branchDistanceVector))
                .build();
    }

    /**
     * Computes the branch distance vector (approach levels + branch distances)
     * for the given branch vertices based on the CFG.
     *
     * @param visitedVertices The list of visited vertices (traces).
     * @param branchVertices The branch vertices (targets).
     * @return Returns the branch distance vector.
     */
    private List<String> computeBranchDistanceVectorCFG(final List<CFGVertex> visitedVertices,
                                                        final List<CFGVertex> branchVertices) {

        final var vector = new String[branchVertices.size()];
        IntStream.range(0, branchVertices.size())
                .parallel()
                .forEach(index -> {
                    final var vertex = branchVertices.get(index);
                    final var distance = computeApproachLevelAndBranchDistanceCFG(visitedVertices, vertex);
                    vector[index] = distance;
                });

        final var branchDistanceVector = Arrays.asList(vector);
        return Collections.unmodifiableList(branchDistanceVector);
    }

    /**
     * Computes the branch distance vector (approach levels + branch distances)
     * for the given branch vertices based on the CDG.
     *
     * @param visitedVertices The list of visited vertices (traces).
     * @param branchVertices  The branch vertices (targets).
     * @return Returns the branch distance vector based on the CDG.
     */
    private List<String> computeBranchDistanceVectorCDG(final Set<CFGVertex> visitedVertices,
                                                        final List<CFGVertex> branchVertices,
                                                        final List<String> traces) {

        final var vector = new String[branchVertices.size()];
        IntStream.range(0, branchVertices.size())
                .parallel()
                .forEach(index -> {
                    final var vertex = branchVertices.get(index);
                    final var distance = computeApproachLevelAndBranchDistanceCDG(visitedVertices, vertex, traces);
                    vector[index] = distance;
                });

        final var branchDistanceVector = Arrays.asList(vector);
        return Collections.unmodifiableList(branchDistanceVector);
    }

    /**
     * Retrieves the stack trace (lines).
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace (lines).
     */
    private Message getStackTrace(Message request) {

        if (!(graph instanceof CallTree)) {
            throw new UnsupportedOperationException("Crash reproduction only available on call tree so far!");
        }

        final CallTree callTree = (CallTree) graph;

        return new Message.MessageBuilder("/graph/stack_trace")
                .withParameter("stack_trace", String.join(",", callTree.getStackTrace().getAtLines()))
                .build();
    }

    /**
     * Retrieves the stack trace tokens.
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace tokens.
     */
    private Message getStackTraceTokens(Message request) {

        if (!(graph instanceof CallTree)) {
            throw new UnsupportedOperationException("Crash reproduction only available on call tree so far!");
        }

        final CallTree callTree = (CallTree) graph;

        final StackTrace stackTrace = callTree.getStackTrace();
        final String packageName = request.getParameter("package");
        final Set<String> stackTraceTokens = stackTrace.getFuzzyTokens(packageName);
        final Stream<String> instructionTokens = callTree.getTokensForStackTrace(stackTrace, packageName);
        final Set<String> tokens = Stream.concat(stackTraceTokens.stream(), instructionTokens).collect(Collectors.toSet());

        final var builder = new Message.MessageBuilder("/graph/stack_trace_tokens")
                .withParameter("tokens", String.valueOf(tokens.size()));

        int pos = 0;
        for (String token : tokens) {
            builder.withParameter("token_" + pos, token);
            pos++;
        }

        return builder.build();
    }

    /**
     * Retrieves the stack trace user tokens.
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace user tokens.
     */
    private Message getStackTraceUserTokens(Message request) {

        if (!(graph instanceof CallTree)) {
            throw new UnsupportedOperationException("Crash reproduction only available on call tree so far!");
        }

        final CallTree callTree = (CallTree) graph;

        return new Message.MessageBuilder("/graph/stack_trace_user_tokens")
                .withParameter("tokens", String.join(",", callTree.getStackTrace().getUserTokens()))
                .build();
    }

    /**
     * Retrieves the crash distance for the given chromosome.
     *
     * @param request The request message.
     * @return Returns a response message containing the computed crash distance.
     */
    private Message getCrashDistance(final Message request) {

        if (!(graph instanceof CallTree)) {
            throw new UnsupportedOperationException("Crash reproduction only available on call tree so far!");
        }

        CallTree callTree = (CallTree) graph;

        final String chromosome = request.getParameter("chromosome");
        final List<Set<String>> tracesPerFile = getTracesPerFile(request);
        final Set<String> traces = getTraces(request);
        double crashDistance = callTree.getCrashDistance(chromosome, tracesPerFile, traces);

        return new Message.MessageBuilder("/graph/get_crash_distance")
                .withParameter("crash_distance", String.valueOf(crashDistance))
                .build();
    }

    /**
     * Retrieves the traces for a single chromosome or all if unspecified.
     *
     * @param request The request message.
     * @return Returns the traces for a single chromosome or all if unspecified.
     */
    private Set<String> getTraces(Message request) {
        return new HashSet<>(readTraces(getTraceFiles(request)));
    }

    /**
     * Retrieves the traces per file. One such file essentially represents the traces of a single action.
     *
     * @param request The request message.
     * @return Returns the traces per file / action.
     */
    private List<Set<String>> getTracesPerFile(Message request) {
        return getTraceFiles(request).stream()
                .map(f -> new HashSet<>(readTraces(List.of(f))))
                .collect(Collectors.toList());
    }

    /**
     * Retrieves the trace files for the given chromosome or all if unspecified.
     *
     * @param request The request message containing the chromosome identifier.
     * @return Returns the trace files belonging to the chromosome.
     */
    private List<File> getTraceFiles(Message request) {

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");

        // collect the relevant traces files
        final Path appDir = appsDir.resolve(packageName);
        final File tracesDir = appDir.resolve("traces").toFile();
        return getTraceFiles(tracesDir, chromosome);
    }

    /**
     * Retrieves the visited methods described by the given traces.
     *
     * @param traces The given traces.
     * @return Returns the set of visited methods.
     */
    private Set<String> getVisitedMethods(final Set<String> traces) {
        return traces.stream().map(this::traceToMethod).collect(Collectors.toSet());
    }

    /**
     * Retrieves the fully-qualified method name from the given trace.
     *
     * @param trace The given trace.
     * @return Returns the method name encapsulated in the trace.
     */
    private String traceToMethod(final String trace) {
        final String[] parts = trace.split("->");
        return parts[0] + "->" + parts[1];
    }

    /**
     * Draws the graph and saves it inside the app directory.
     *
     * @param request The request message.
     * @return Returns a message indicating that the graph could be drawn.
     */
    private Message drawGraph(Message request) {

        if (graph == null) {
            throw new IllegalStateException("Graph hasn't been initialised!");
        }

        final boolean raw = Boolean.parseBoolean(request.getParameter("raw"));

        final File appDir = new File(appsDir.toFile(), graph.getAppName());
        final File drawDir = new File(appDir, "graph-drawings");
        drawDir.mkdirs();

        if (raw) {
            Log.println("Drawing raw graph...");
            graph.draw(drawDir);
        } else {
            Log.println("Drawing graph...");

            final Set<Vertex> targetVertices = new HashSet<>(this.targetVertices);

            // retrieve the visited vertices
            final Set<Vertex> visitedVertices = new HashSet<>(getVisitedVertices(appDir, null));

            // draw the graph where target and visited vertices are marked in different colours
            graph.draw(drawDir, visitedVertices, targetVertices);
        }

        return new Message("/graph/draw");
    }

    /**
     * Draws the call tree.
     *
     * @param request The request request.
     * @return Returns an empty response request.
     */
    @SuppressWarnings("unused")
    private Message drawCallTree(Message request) {

        CallTree callTree = (CallTree) graph;

        final Set<String> traces = getTraces(request);
        final Set<String> visitedMethods = getVisitedMethods(traces);

        final File appDir = new File(appsDir.toFile(), graph.getAppName());
        final File drawDir = new File(appDir, "graph-drawings");
        drawDir.mkdirs();

        final String id = request.getParameter("id");
        final File dotFile = new File(drawDir, id + ".dot");

        // highlight the visited methods in red and the target methods in blue
        final Map<String, String> highlightMethods = visitedMethods.stream()
                .collect(Collectors.toMap(Function.identity(), a -> "red"));
        targetVertices.stream()
                .map(v -> (CFGVertex) v)
                .map(CFGVertex::getMethod)
                .forEach(target -> highlightMethods.put(target, "blue"));

        // export the call tree to a dot file
        callTree.toDot(dotFile, highlightMethods);

        return new Message("/graph/draw/call_tree");
    }

    /**
     * Retrieves the set (actually a list without duplicates due to performance reasons) of visited vertices by
     * traversing over the specified chromosome traces contained in the app directory.
     *
     * @param appDir The app directory.
     * @param chromosomes A list of chromosomes separated by '+' or {@code null} if all traces should be considered.
     * @return Returns the visited vertices.
     */
    private List<Vertex> getVisitedVertices(final File appDir, final String chromosomes) {

        // get list of traces file
        final File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        final List<File> tracesFiles = getTraceFiles(tracesDir, chromosomes);

        // read traces from trace file(s)
        final List<String> traces = readTraces(tracesFiles);

        return graph.lookupVertices(traces);
    }

    /**
     * Selects one or more target vertices based on the given target criterion.
     *
     * @param target Describes how a target should be selected.
     * @return Returns the selected target vertex.
     */
    private List<? extends Vertex> selectTargetVertices(String target) {

        Log.println("Target vertex selection strategy: " + target);

        switch (target) {
            case "all_branches":
                return ((CFG) graph).getBranchVertices();
            case "all_basic_blocks":
                return ((CFG) graph).getVertices().stream()
                        .filter(vertex -> vertex.getStatement() instanceof BlockStatement &&
                                // A basic block that got split (InterCDG & InterCFG) after an invoke statement remains
                                // in terms of the instrumentation still a single basic block, i.e., there is only a
                                // single trace for the entire basic block.
                                !(((BlockStatement) vertex.getStatement()).getFirstStatement() instanceof ReturnStatement))
                        .collect(Collectors.toList());
            case "random_target":
            case "random_branch":
                final List<? extends Vertex> targets = target.equals("random_target")
                        ? graph.getVertices() : ((CFG) graph).getBranchVertices();

                while (true) {
                    Random rand = new Random();
                    Vertex randomVertex = targets.get(rand.nextInt(targets.size()));

                    if (graph.isReachable(randomVertex)) {
                        Log.println("Randomly selected target vertex: " + randomVertex);
                        return List.of(randomVertex);
                    }
                }
            case "stack_trace":
                final CallTree callTree = (CallTree) graph;
                return callTree.getTargetVertices();
            default:
                // look up target vertex/vertices by supplied trace(s)
                final List<Vertex> targetVertices = Arrays.stream(target.split(","))
                        .map(graph::lookupVertex)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                if (targetVertices.isEmpty()) {
                    throw new UnsupportedOperationException("Custom target vertex/vertices not found: " + target);
                }
                return targetVertices;
        }
    }

    /**
     * Initialises the graph.
     *
     * @param request The request message.
     * @return Returns a dummy response upon successful graph initialization.
     */
    private Message initGraph(Message request) {

        String packageName = request.getParameter("packageName");
        GraphType graphType = GraphType.valueOf(request.getParameter("graph_type"));
        File apkPath = new File(request.getParameter("apk"));
        String target = request.getParameter("target");

        if (!apkPath.exists()) {
            throw new IllegalArgumentException("Can't locate APK: " + apkPath.getAbsolutePath() + "!");
        }

        switch (graphType) {
            case INTRA_CFG: {
                boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));
                String methodName = request.getParameter("method");
                initIntraCFG(apkPath, methodName, useBasicBlocks, packageName, target);
                break;
            }
            case INTER_CFG: {
                boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                initInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, packageName, target);
                break;
            }
            case INTER_CDG: {
                boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                initInterCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, packageName, target);
                break;
            }
            case MODULAR_CDG: {
                boolean useBasicBlocks = Boolean.parseBoolean(request.getParameter("basic_blocks"));
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                initModularCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, packageName, target);
                break;
            }
            case CALL_TREE: {
                boolean excludeARTClasses = Boolean.parseBoolean(request.getParameter("exclude_art_classes"));
                boolean resolveOnlyAUTClasses
                        = Boolean.parseBoolean(request.getParameter("resolve_only_aut_classes"));
                final String stackTracePath = request.getParameter("stack_trace_path");
                initCallTree(apkPath, excludeARTClasses, resolveOnlyAUTClasses, packageName, target, stackTracePath);
                break;
            }
            default:
                throw new UnsupportedOperationException("Graph type not yet supported!");
        }

        return new Message("/graph/init");
    }

    /**
     * Initialises the modularCDG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param useBasicBlocks Whether to use basic blocks for the CDG.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param packageName The package name of the AUT.
     * @param target Describes the target vertices.
     */
    private void initModularCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                                     boolean resolveOnlyAUTClasses, String packageName, String target) {
        graph = new ModularCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        targetVertices = selectTargetVertices(target);
    }

    /**
     * Initialises the intraCFG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param methodName The method for which the intraCFG should be constructed.
     * @param useBasicBlocks Whether to use basic blocks for the intraCFG.
     * @param packageName The package name of the AUT.
     * @param target Describes the target vertices.
     */
    private void initIntraCFG(final File apkPath, final String methodName, final boolean useBasicBlocks,
                                 final String packageName, final String target) {
        graph = new IntraCFG(apkPath, methodName, useBasicBlocks, appsDir, packageName);
        targetVertices = selectTargetVertices(target);
    }

    /**
     * Initialises the interCFG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param useBasicBlocks Whether to use basic blocks for the interCFG.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param packageName The package name of the AUT.
     * @param target Describes the target vertices.
     */
    private void initInterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                                 boolean resolveOnlyAUTClasses, String packageName, String target) {
        graph = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        targetVertices = selectTargetVertices(target);
    }

    /**
     * Initialises the interCDG with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param useBasicBlocks Whether to use basic blocks for the CDG.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param packageName The package name of the AUT.
     * @param target Describes the target vertices.
     */
    private void initInterCDG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                              boolean resolveOnlyAUTClasses, String packageName, String target) {
        graph = new InterCDG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        targetVertices = selectTargetVertices(target);
    }

    /**
     * Initialises the call tree with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to resolve only classes belonging to the AUT package.
     * @param packageName The package name of the AUT.
     * @param target Describes the target vertices.
     * @param stackTracePath The path to the stack trace.
     */
    private void initCallTree(File apkPath, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                              String packageName, String target, String stackTracePath) {
        graph = new CallTree(apkPath, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName, stackTracePath);
        targetVertices = selectTargetVertices(target);
    }

    /**
     * Retrieves the traces for the given chromosome.
     *
     * @param packageName The package name of the AUT.
     * @param chromosome The chromosome for which the traces should be retrieved.
     * @return Returns the traces for the given chromosome.
     */
    private List<String> getTraces(final String packageName, final String chromosome) {
        final Path appDir = appsDir.resolve(packageName);
        final File tracesDir = appDir.resolve("traces").toFile();
        final List<File> tracesFiles = getTraceFiles(tracesDir, chromosome);
        return readTraces(tracesFiles);
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
    private List<String> readTraces(List<File> tracesFiles) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        Set<String> traces = new LinkedHashSet<>();

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
        return new ArrayList<>(traces);
    }
}
