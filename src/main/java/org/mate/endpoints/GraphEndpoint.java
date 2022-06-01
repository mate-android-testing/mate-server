package org.mate.endpoints;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import org.apache.commons.io.FileUtils;
import org.mate.graphs.Graph;
import org.mate.graphs.GraphType;
import org.mate.graphs.InterCFG;
import org.mate.graphs.IntraCFG;
import org.mate.network.Endpoint;
import org.mate.network.message.Message;
import org.mate.util.AndroidEnvironment;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
    private final Path appsDir;

    // a target vertex (a random branch)
    private Vertex targetVertex;

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
        } else if (request.getSubject().startsWith("/graph/draw")) {
            return drawGraph(request);
        } else {
            throw new IllegalArgumentException("Message request with subject: "
                    + request.getSubject() + " can't be handled by GraphEndpoint!");
        }
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
            Set<Vertex> targetVertices = new HashSet<>();

            if (targetVertex != null) {
                targetVertices.add(targetVertex);
            } else {
                targetVertices.addAll(new HashSet<>(graph.getBranchVertices()));
            }

            // retrieve the visited vertices
            Set<Vertex> visitedVertices = getVisitedVertices(appDir);

            // draw the graph where target and visited vertices are marked in different colours
            graph.draw(drawDir, visitedVertices, targetVertices);
        }

        return new Message("/graph/draw");
    }

    private Set<Vertex> getVisitedVertices(File appDir) {

        // get list of traces file
        File tracesDir = new File(appDir, "traces");

        // collect the relevant traces files
        List<File> tracesFiles = getTraceFiles(tracesDir, null);

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
    private Vertex selectTargetVertex(String target) {

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
                        return randomVertex;
                    }
                }
            default:
                Vertex targetVertex = graph.lookupVertex(target);

                if (targetVertex == null) {
                    throw new UnsupportedOperationException("Custom target vertex not found: " + target);
                }
                return targetVertex;
        }
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
        targetVertex = selectTargetVertex(target);
        return new Message("/graph/init");
    }

    private Message initInterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses,
                                 boolean resolveOnlyAUTClasses, String packageName, String target) {
        graph = new InterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses, appsDir, packageName);
        targetVertex = selectTargetVertex(target);
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

        long end = System.currentTimeMillis();
        Log.println("Computing approach level took: " + (end - start) + " ms.");

        // the normalised fitness value in the range [0,1]
        String branchDistance;

        if (minDistance.get() == Integer.MAX_VALUE) {
            // branch not reachable by execution path
            branchDistance = String.valueOf(1);
        } else if (minDistance.get() == 0) {
            // covered target branch
            branchDistance = String.valueOf(0);
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
            double normalisedBranchDistance = minBranchDistance / (minBranchDistance + 1);
            double combined = approachLevel + normalisedBranchDistance;
            combined = combined / (combined + 1);
            branchDistance = String.valueOf(combined);
        }

        return new Message.MessageBuilder("/graph/get_branch_distance")
                .withParameter("branch_distance", branchDistance)
                .build();
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

                int minBranchDistance = Integer.MAX_VALUE;

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
                        int distance = Integer.parseInt(trace.split(":")[1]);

                        // we need to track the minimal distance > 0 (non-covered branch)
                        if (distance > 0 && distance < minBranchDistance) {
                            minBranchDistance = distance;
                        }
                    }
                }

                Log.println("Approach level: " + approachLevel);
                Log.println("Minimal branch distance: " + minBranchDistance);

                // combine and normalise
                float normalisedBranchDistance = (float) minBranchDistance / (minBranchDistance + 1);
                float combined = approachLevel + normalisedBranchDistance;
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
