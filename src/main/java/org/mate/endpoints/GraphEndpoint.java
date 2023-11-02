package org.mate.endpoints;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.ReturnStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.Tuple;
import org.apache.commons.io.FileUtils;
import org.jf.dexlib2.analysis.AnalyzedInstruction;
import org.mate.crash_reproduction.*;
import org.mate.graphs.*;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Stores for each stack trace line detailed information.
     */
    private Map<AtStackTraceLine, AnalyzedStackTraceLine> analyzedStackTraceLines;

    /**
     * The stack trace used for crash reproduction.
     */
    private StackTrace stackTrace;

    /**
     * Provides mainly utility functions for crash reproduction.
     */
    private CrashReproductionUtil crashReproductionUtil;

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
                // This is the if or switch statement from which an incorrect branch toward the target was taken.
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

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance for the chromosome: " + chromosome);

        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = mapTracesToVertices(graph, traces).stream()
                .map(vertex -> (CFGVertex) vertex)
                .collect(Collectors.toSet());
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

        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = mapTracesToVertices(graph, traces).stream()
                .map(vertex -> (CFGVertex) vertex)
                .collect(Collectors.toList());

        InterCFG interCFG = (InterCFG) graph;
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

        long start = System.currentTimeMillis();
        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = mapTracesToVertices(graph, traces).stream()
                .map(vertex -> (CFGVertex) vertex)
                .collect(Collectors.toList());
        final var branchVertices =  ((CFG) graph).getBranchVertices();
        long start1 = System.currentTimeMillis();
        InterCFG interCFG = (InterCFG) graph;
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

        final String packageName = request.getParameter("packageName");
        final String chromosome = request.getParameter("chromosome");
        Log.println("Computing the branch distance vector for the chromosome: " + chromosome);

        long start = System.currentTimeMillis();
        final var traces = getTraces(packageName, chromosome);
        final var visitedVertices = mapTracesToVertices(graph, traces).stream()
                .map(vertex -> (CFGVertex) vertex)
                .collect(Collectors.toSet());
        final var branchVertices =  ((CFG) graph).getBranchVertices();

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
    private List<String> computeBranchDistanceVectorCFG(final List<CFGVertex> visitedVertices, final List<CFGVertex> branchVertices) {

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
        return new Message.MessageBuilder("/graph/stack_trace")
                .withParameter("stack_trace", String.join(",", stackTrace.getAtLines()))
                .build();
    }

    /**
     * Retrieves the stack trace tokens.
     *
     * @param request The request message.
     * @return Returns a response message containing the stack trace tokens.
     */
    private Message getStackTraceTokens(Message request) {

        final String packageName = request.getParameter("package");
        final Set<String> stackTraceTokens = stackTrace.getFuzzyTokens(packageName);
        final Stream<String> instructionTokens = crashReproductionUtil.getTokensForStackTrace(stackTrace, packageName);
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
        return new Message.MessageBuilder("/graph/stack_trace_user_tokens")
                .withParameter("tokens", String.join(",", stackTrace.getUserTokens()))
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
     * Computes the normalized basic block distance between the given traces and the target methods described by the
     * stack trace.
     *
     * @param tracesPerFile The given traces per file. One file essentially represents the traces of a single action.
     * @return Returns a mapping that describes for each stack trace line the normalized basic block distance.
     */
    private Map<AtStackTraceLine, Double> getNormalizedBasicBlockDistances(final List<Set<String>> tracesPerFile) {

        // Look for the traces that reached most target methods.
        final var bestTraces = tracesPerFile.stream()
                .map(traces -> new Tuple<>(traces, reachedTargetMethods(traces)))
                .max(Comparator.comparingLong(tuple -> tuple.getY().values().stream().filter(b -> b).count()))
                .orElseThrow();

        return bestTraces.getY().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    final int distance = e.getValue()
                            // only need to compute distance if we reached the target method (stack trace line)
                            ? getBasicBlockDistance(bestTraces.getX(), e.getKey())
                            : Integer.MAX_VALUE;

                    // normalize distance in [0,1]
                    return distance == Integer.MAX_VALUE
                            ? 1D
                            : (double) distance / ((double) distance + 1);
                }));
    }

    /**
     * Retrieves the minimal basic block distance (approach level) between the given traces and the target method
     * contained in the stack trace.
     *
     * @param traces The set of traces.
     * @param stackTraceLine The stack trace line containing the target method.
     * @return Returns the minimal basic block distance between the traces and the target method.
     */
    private int getBasicBlockDistance(final Set<String> traces, final AtStackTraceLine stackTraceLine) {

        // retrieve the intra CFG corresponding to the given stack trace line
        final var analyzedStackTraceLine = analyzedStackTraceLines.get(stackTraceLine);
        final IntraCFG intraCFG = analyzedStackTraceLine.getIntraCFG();

        final String targetMethod = analyzedStackTraceLine.getIntraCFGVertices().stream()
                .findAny().orElseThrow().getMethod();

        int minDistance = Integer.MAX_VALUE;

        for (String trace : traces) {
            if (traceToMethod(trace).equals(targetMethod)) {
                int distance = analyzedStackTraceLine.getIntraCFGVertices().stream()
                        // TODO: Employ a cache for the distances!
                        .map(targetVertex -> intraCFG.getDistance(intraCFG.lookupVertex(trace), (CFGVertex) targetVertex))
                        .map(dist -> dist == -1 ? Integer.MAX_VALUE : dist) // -1 means not reachable
                        .min(Integer::compare)
                        .orElseThrow();

                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
        }

        return minDistance;
    }

    /**
     * Retrieves the normalized call tree distance for the given chromosome.
     *
     * @param chromosome The chromosome for which the call tree distance should be derived.
     * @param tracesPerFile The traces per file (action).
     * @return Returns the normalized call tree distance for the given chromosome.
     */
    private double getCallTreeDistance(final String chromosome, final List<Set<String>> tracesPerFile) {

        Log.println("Computing the call tree distance for the chromosome: " + chromosome);

        // We don't want to mix the traces of different actions, since our target action should produce all traces
        // necessary to cover the stack trace methods.
        // If we mix the traces then it's possible that we get a call tree distance of zero even if the target methods
        // are called from different actions
        // (and never just by one action). Then we have technically reached all target methods, but not in the right sequence
        double callTreeDistance = tracesPerFile.stream()
                .map(traces -> traces.stream().map(this::traceToMethod).collect(Collectors.toSet()))
                .mapToInt(this::getCallTreeDistance)
                .min().orElseThrow();

        double normalizedCallTreeDistance = callTreeDistance == Integer.MAX_VALUE
                ? 1
                : callTreeDistance / (callTreeDistance + 1);

        Log.println("Call tree distance for " + chromosome + " is: abs. distance " + callTreeDistance
                + ", rel. distance " + normalizedCallTreeDistance);

        return normalizedCallTreeDistance;
    }

    /**
     * Retrieves the normalized (average) basic block distance between the traces and the target methods.
     *
     * @param chromosome The chromosome for which the basic block distance should be derived.
     * @param tracesPerFile The traces per file (action).
     * @return Returns the normalized basic block distance for the given chromosome.
     */
    private double getBasicBlockDistance(final String chromosome, final List<Set<String>> tracesPerFile) {

        Log.println("Computing the call tree distance for the chromosome: " + chromosome);

        final Map<AtStackTraceLine, Double> basicBlockDistances = getNormalizedBasicBlockDistances(tracesPerFile);

        // computes the average basic block distance
        double sum = basicBlockDistances.values().stream().mapToDouble(d -> d).sum();
        double averageBasicBlockDistance = sum / basicBlockDistances.size();

        Log.println("Basic block distance for " + chromosome + " is: " + averageBasicBlockDistance);

        return averageBasicBlockDistance;
    }

    /**
     * Retrieves the number (percentage) of reached constructors for the given chromosome.
     *
     * @param chromosome The chromosome for which the number of reached constructors should be derived.
     * @param traces The traces for the given chromosome.
     * @return Returns the number of reached constructors for the given chromosome.
     */
    private double getNumberOfReachedConstructors(final String chromosome, final Set<String> traces) {

        Log.println("Computing number of reached constructors for the chromosome: " + chromosome);

        // track which methods have been visited by the traces
        final Set<String> reachedMethods = traces.stream().map(this::traceToMethod).collect(Collectors.toSet());

        // TODO: Cache this computation when initialising the call graph.
        // track the set of required constructors by iterating over the stack trace lines
        final Set<String> requiredConstructors = analyzedStackTraceLines.values().stream()
                .map(AnalyzedStackTraceLine::getRequiredConstructorCalls)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        // count how many constructors have been reached
        double reachedConstructors = requiredConstructors.stream().filter(reachedMethods::contains).count();

        // normalize in the range [0,1]
        double normalisedNumberOfReachedConstructors = requiredConstructors.size() == 0
                ? 1
                : reachedConstructors / requiredConstructors.size();

        Log.println("Number of reached constructors for " + chromosome + " is: " + normalisedNumberOfReachedConstructors);

        return normalisedNumberOfReachedConstructors;
    }

    /**
     * Retrieves the crash distance for the given chromosome.
     *
     * @param request The request message.
     * @return Returns a response message containing the computed crash distance.
     */
    private Message getCrashDistance(final Message request) {

        final String chromosome = request.getParameter("chromosome");
        final List<Set<String>> tracesPerFile = getTracesPerFile(request);
        final Set<String> traces = getTraces(request);

        double callTreeDistance = getCallTreeDistance(chromosome, tracesPerFile);
        double basicBlockDistance = getBasicBlockDistance(chromosome, tracesPerFile);
        double reachedConstructorsPercentage = getNumberOfReachedConstructors(chromosome, traces);

        double crashDistance = (basicBlockDistance + callTreeDistance + reachedConstructorsPercentage) / 3;

        return new Message.MessageBuilder("/graph/get_crash_distance")
                .withParameter("crash_distance", String.valueOf(crashDistance))
                .build();
    }

    /**
     * Computes the call tree distance between the target vertices and the given traces.
     *
     * @param traces The given traces.
     * @return Returns the call tree distance.
     */
    private int getCallTreeDistance(final Set<String> traces) {

        CallTree callTree = (CallTree) graph;

        // TODO: Cache this computation.
        // the call tree vertices describing the stack trace in reversed order
        final List<CallTreeVertex> callTreeVertices = targetVertices.stream()
                .map(v -> (CFGVertex) v)
                .map(CFGVertex::getMethod)
                .map(CallTreeVertex::new)
                .collect(Collectors.toList());
        Collections.reverse(callTreeVertices);

        Optional<CallTreeVertex> lastCoveredVertex = Optional.empty();

        while (!callTreeVertices.isEmpty() && traces.contains(callTreeVertices.get(0).getMethod())) {
            // remove target vertices that we have already covered
            lastCoveredVertex = Optional.of(callTreeVertices.remove(0));
        }

        if (callTreeVertices.isEmpty()) {
            // We have already reached all targets, thus a distance of 0.
            return 0;
        } else if (lastCoveredVertex.isPresent()) {
            // We partially covered the targets, thus the distance is defined as the minimal path length from the last
            // covered vertex through the remaining targets.
            return callTree.getShortestPathWithStops(lastCoveredVertex.get(), callTreeVertices).orElseThrow().getLength();
        } else {
            // TODO: Computing the minimal path between every single trace and the targets can be expensive. Track it
            //  or compute the distance in advance. Alternatively, use a different metric in this case.
            // We have not found any targets yet, thus the distance is defined as the minimal path length from a trace
            // through the targets.
            int minDistance = Integer.MAX_VALUE;

            for (String trace : traces) {
                var path
                        = callTree.getShortestPathWithStops(new CallTreeVertex(trace), callTreeVertices);
                if (path.isPresent()) {
                    final int distance = path.get().getLength();

                    if (distance < minDistance) {
                        minDistance = distance;
                    }
                }
            }
            return minDistance;
        }
    }

    /**
     * Computes a mapping that describes which stack trace line (target method) has been covered by the given traces.
     *
     * @param traces The given traces.
     * @return Returns a mapping that tracks which target method (stack trace line) has been covered by the traces.
     */
    private Map<AtStackTraceLine, Boolean> reachedTargetMethods(final Set<String> traces) {

        final Set<String> reachedMethods = traces.stream().map(this::traceToMethod).collect(Collectors.toSet());

        final Map<AtStackTraceLine, Boolean> reachedTargetMethods = analyzedStackTraceLines.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, stackTraceLine -> {
                    final String method = expectOne(stackTraceLine.getValue().getInterCFGVertices().stream()
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));
                    return reachedMethods.contains(method);
                }));
        onlyAllowCoveredIfPredecessorCoveredAsWell(reachedTargetMethods);
        return reachedTargetMethods;
    }

    // TODO: Need help here for understanding!
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

    /**
     * Checks whether the given collection contains exactly one element.
     *
     * @param collection The collection to be verified.
     * @param <T> The element type of the collection entries.
     * @return Returns the single element in the collection or throws an exception otherwise.
     */
    private static <T> T expectOne(final Collection<T> collection) {
        if (collection.isEmpty()) {
            throw new NoSuchElementException("Empty collection!");
        } else if (collection.size() > 1) {
            throw new IllegalArgumentException("Collection contains more than one element!");
        } else {
            return collection.stream().findAny().orElseThrow();
        }
    }

    /**
     * Computes the traces for the given statement. A trace encodes the full-qualified method name and the instruction
     * index, e.g. Lcom/zola/bmi/onStop()V->3.
     *
     * @param statement The given statement.
     * @return Returns the traces for the statement.
     */
    private Stream<String> tracesForStatement(final Statement statement) {
        return getInstructions(statement)
                .map(instruction -> statement.getMethod() + "->" + instruction.getInstructionIndex());
    }

    /**
     * Retrieves the instructions of the given statement.
     *
     * @param statement The given statement.
     * @return Returns the instructions belonging to the statement.
     */
    private static Stream<AnalyzedInstruction> getInstructions(final Statement statement) {
        if (statement instanceof BasicStatement) {
            return Stream.of(((BasicStatement) statement).getInstruction());
        } else if (statement instanceof BlockStatement) { // basic block, unroll instructions
            return ((BlockStatement) statement).getStatements()
                    .stream().flatMap(GraphEndpoint::getInstructions);
        } else {
            return Stream.empty();
        }
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

        return mapTracesToVertices(graph, traces);
    }

    /**
     * Selects one or more target vertices based on the given target criterion.
     *
     * @param target Describes how a target should be selected.
     * @param packageName The package name of the AUT.
     * @param apkPath The path to the APK file.
     * @param stackTracePath The path to the stack trace file, {@code null} if not required.
     * @return Returns the selected target vertex.
     */
    private List<? extends Vertex> selectTargetVertices(String target, String packageName, File apkPath, String stackTracePath) {

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
                final File appDir = new File(appsDir.toFile(), packageName);

                // the stack_trace.txt should be located within the app directory
                final File stackTraceFile = new File(appDir, stackTracePath);

                if (!stackTraceFile.exists()) {
                    throw new IllegalArgumentException("Stack trace file does not exist at: " + stackTraceFile.getAbsolutePath());
                }

                stackTrace = parseStackTraceFromFile(stackTraceFile);

                return getTargetVertices(stackTrace, packageName, apkPath);
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
     * Parses the stack trace from the given file.
     *
     * @param stackTraceFile The given stack trace file.
     * @return Returns the parsed stack trace.
     */
    private StackTrace parseStackTraceFromFile(final File stackTraceFile) {
        try {
            return StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()));
        } catch (IOException e) {
            Log.printError("Could not read stack trace file from '" + stackTraceFile.getAbsolutePath() + "'!");
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Initialises the target vertices for crash reproduction.
     *
     * @param stackTrace The target stack trace.
     * @param packageName The package name of the AUT.
     * @param apkPath The path to the APK.
     * @return Returns the target vertices for crash reproduction.
     */
    private List<? extends Vertex> getTargetVertices(final StackTrace stackTrace, final String packageName, final File apkPath) {

        final CallTree callTree = (CallTree) graph;
        final InterCFG interCFG = callTree.getInterCFG();

        // TODO: Make this crash reproduction util a real utility class.
        crashReproductionUtil = new CrashReproductionUtil(callTree);

        // Analyse every 'at' stack trace line that belongs to the given package and comes in consecutive order.
        analyzedStackTraceLines = crashReproductionUtil.getLastConsecutiveLines(stackTrace.getStackTraceAtLines()
                .collect(Collectors.toList()), packageName).stream()
                .collect(Collectors.toMap(Function.identity(), line -> {

                    // Retrieve the inter-procedural CFG vertices that are mapped to the given stack trace line.
                    final Set<CFGVertex> targetInterCFGVertices
                            = crashReproductionUtil.getTargetVerticesForStackTraceLine(line, interCFG);

                    // TODO: Retrieve the target method name directly from the method name encoded in the stack trace line.
                    final String targetMethod = expectOne(targetInterCFGVertices.stream()
                            .map(CFGVertex::getMethod)
                            .collect(Collectors.toSet()));

                    // create the intraCFG matching the target method (method encoded in the stack trace line)
                    final IntraCFG intraCFG = new IntraCFG(apkPath, targetMethod, true, appsDir, packageName);

                    // TODO: Remove once we can assure that those vertices are identical to the interTargetVertices!
                    final Set<CFGVertex> targetIntraCFGVertices = targetInterCFGVertices.stream()
                            .flatMap(interVertex -> tracesForStatement(interVertex.getStatement()))
                            .map(intraCFG::lookupVertex)
                            .collect(Collectors.toSet());

                    if (!targetInterCFGVertices.equals(targetIntraCFGVertices)) {
                        Log.println("Not same set of vertices!");
                        Log.println("InterCFG vertices: " + targetInterCFGVertices);
                        Log.println("IntraCFG vertices: " + targetIntraCFGVertices);
                    }

                    // Retrieves the required constructors to properly call the target method in the stack trace line.
                    final var requiredConstructorCalls = crashReproductionUtil.getRequiredConstructorCalls(line);

                    return new AnalyzedStackTraceLine(targetInterCFGVertices, intraCFG,
                            targetIntraCFGVertices, requiredConstructorCalls);
                }));

        // Retrieve the target vertices from the stack trace lines.
        final List<CFGVertex> targetInterCFGVertices = stackTrace.getStackTraceAtLines()
                .filter(analyzedStackTraceLines::containsKey)
                .map(analyzedStackTraceLines::get)
                .map(AnalyzedStackTraceLine::getInterCFGVertices)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        // At least a single line (target) in the stack trace must refer to the AUT.
        if (targetInterCFGVertices.isEmpty()) {
            throw new IllegalStateException("No targets found for stack trace!");
        }

        // TODO: Store the call tree vertices in a global variable.
        // Map the interCFG vertices to the callTree vertices.
        final var callTreeVertices = targetInterCFGVertices.stream()
                .map(CFGVertex::getMethod)
                .map(CallTreeVertex::new)
                .collect(Collectors.toList());

        // TODO: Why do we reverse the list?
        Collections.reverse(callTreeVertices);

        // The target vertices must be reachable in the call tree.
        if (callTree.getShortestPathWithStops(callTreeVertices).isEmpty()) {
            throw new IllegalStateException("No path from root to target vertices!");
        }

        return targetInterCFGVertices;
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
        targetVertices = selectTargetVertices(target, packageName, apkPath, null);
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
        targetVertices = selectTargetVertices(target, packageName, apkPath, null);
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
        targetVertices = selectTargetVertices(target, packageName, apkPath, null);
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
        targetVertices = selectTargetVertices(target, packageName, apkPath, null);
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
        graph = new CallTree(apkPath, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        targetVertices = selectTargetVertices(target, packageName, apkPath, stackTracePath);
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

    /**
     * Maps an entry trace to its vertex.
     *
     * @param graph The underlying graph.
     * @param visitedVertices The set of visited vertices.
     * @param trace The potential entry trace.
     */
    @SuppressWarnings("unused")
    private static void mapEntryTraceToVertex(final Graph graph, final Set<Vertex> visitedVertices, final String trace) {

        // mark virtual entry
        final String entryMarker = "->entry";
        final int entryIndex = trace.indexOf(entryMarker);
        if (entryIndex != -1) {
            final String entryTrace = trace.substring(0, entryIndex + entryMarker.length());
            final Vertex visitedEntry = graph.lookupVertex(entryTrace);

            if (visitedEntry != null) {
                visitedVertices.add(visitedEntry);
            } else {
                Log.printWarning("Couldn't derive vertex for entry trace: " + entryTrace);
            }
        }
    }

    /**
     * Maps an exit trace to its vertex.
     *
     * @param graph The underlying graph.
     * @param visitedVertices The set of visited vertices.
     * @param trace The potential exit trace.
     */
    @SuppressWarnings("unused")
    private static void mapExitTraceToVertex(final Graph graph, final Set<Vertex> visitedVertices, final String trace) {

        // mark virtual exit
        final String exitMarker = "->exit";
        final int exitIndex = trace.indexOf(exitMarker);
        if (exitIndex != -1) {
            final String exitTrace = trace.substring(0, exitIndex + exitMarker.length());
            final Vertex visitedExit = graph.lookupVertex(exitTrace);

            if (visitedExit != null) {
                visitedVertices.add(visitedExit);
            } else {
                Log.printWarning("Couldn't derive vertex for exit trace: " + exitTrace);
            }
        }
    }

    /**
     * Maps the given set of traces to vertices in the graph.
     *
     * @param traces The set of traces that should be mapped to vertices.
     * @return Returns the vertices described by the given set of traces.
     */
    public static List<Vertex> mapTracesToVertices(final Graph graph, final List<String> traces) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        final Set<Vertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<Vertex, Boolean>());

        // map trace to vertex
        traces.parallelStream().forEach(trace -> {

            if (trace.contains(":")) {
                // skip branch distance trace and traces without a matching vertex pair.
                return;
            }

            // mapEntryTraceToVertex(graph, visitedVertices, trace);
            // mapExitTraceToVertex(graph, visitedVertices, trace);

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
        return new ArrayList<>(visitedVertices);
    }
}
