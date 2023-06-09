package org.mate.graphs;

import static org.junit.Assert.assertEquals;


import org.junit.Before;
import org.junit.Test;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;

public class InterCDGTest {
    private final File RESOURCES = new File("./src/test/java/resources/");
    private final File APK_FILE = new File("./src/test/java/resources/com.zola.bmi.apk");
    private final File TRACES_FILE = new File("./src/test/java/resources/com.zola.bmi/trace1.txt");

    private InterCDG cdg;

    private List<String> readTraceFile(File traceFile) {
        try (Stream<String> stream = Files.lines(traceFile.toPath(), StandardCharsets.UTF_8)) {
            return stream.collect(Collectors.toList());
        } catch (IOException e) {
            Log.println("Reading traces.txt failed!");
            throw new IllegalStateException(e);
        }
    }

    // Copy from GraphEndpoint
    private List<Vertex> mapTracesToVertices(List<String> traces, InterCDG cdg) {

        // read traces from trace file(s)
        long start = System.currentTimeMillis();

        // we need to mark vertices we visited
        Set<CFGVertex> visitedVertices = Collections.newSetFromMap(new ConcurrentHashMap<CFGVertex, Boolean>());

        // map trace to vertex
        traces.parallelStream().forEach(trace -> {

            if (trace.contains(":")) {
                // skip branch distance trace
                return;
            }

            // mark virtual entry
            final String entryMarker = "->entry";
            final int entryIndex = trace.indexOf(entryMarker);
            if (entryIndex != -1) {
                final String entryTrace = trace.substring(0, entryIndex + entryMarker.length());
                final Vertex visitedEntry = cdg.lookupVertex(entryTrace);

                if (visitedEntry != null) {
                    visitedVertices.add((CFGVertex) visitedEntry);
                } else {
                    Log.printWarning("Couldn't derive vertex for entry trace: " + entryTrace);
                }
            }

            // mark virtual exit
            final String exitMarker = "->exit";
            final int exitIndex = trace.indexOf(exitMarker);
            if (exitIndex != -1) {
                final String exitTrace = trace.substring(0, exitIndex + exitMarker.length());
                final Vertex visitedExit = cdg.lookupVertex(exitTrace);

                if (visitedExit != null) {
                    visitedVertices.add((CFGVertex) visitedExit);
                } else {
                    Log.printWarning("Couldn't derive vertex for exit trace: " + exitTrace);
                }
            }

            // mark actual vertex corresponding to trace
            Vertex visitedVertex = cdg.lookupVertex(trace);

            if (visitedVertex == null) {
                Log.printWarning("Couldn't derive vertex for trace: " + trace);
            } else {
                visitedVertices.add((CFGVertex) visitedVertex);
            }
        });

        long end = System.currentTimeMillis();
        Log.println("Mapping traces to vertices took: " + (end - start) + " ms.");

        Log.println("Number of visited vertices: " + visitedVertices.size());
        return new ArrayList<>(visitedVertices);
    }


    @Before
    public void setup(){
        Log.registerLogger(new Log());
        cdg = new InterCDG(APK_FILE, true, false, true, RESOURCES.toPath(), "com.zola.bmi");
        cdg.initTraceToVertexCache();
    }

    @Test
    public void testComputeApproachLevel() {
        CFGVertex target = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->82");
        List<Vertex> visited = new ArrayList<>();
        visited.add(cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->calculateClickHandler(Landroid/view/View;)V->if->13"));
        CFGVertex expectedShortest = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->if->11");
        visited.add(expectedShortest);
        Pair<CFGVertex, Integer> result = cdg.computeApproachLevel(target, visited);
        assertEquals(result.fst(), expectedShortest);
        assertEquals(result.snd(), Integer.valueOf(4));
    }

    @Test
    public void computeBranchDistance() {
        List<String> traces = this.readTraceFile(TRACES_FILE);
        CFGVertex branchVertex = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->calculateClickHandler(Landroid/view/View;)V->if->70");
        assertEquals(cdg.computeBranchDistance(branchVertex, traces), 1, 0);
    }

    @Test
    public void computeApproachLevelAndBranchDistance() {
        List<String> traces = this.readTraceFile(TRACES_FILE);
        List<Vertex> covered = this.mapTracesToVertices(traces, cdg);
        CFGVertex target = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->76");
        Pair<CFGVertex, Integer> result = cdg.computeApproachLevel(target, covered);
        int approachLevel = result.snd();
        double branchDistance = cdg.computeBranchDistance(result.fst(), traces);
        assertEquals(approachLevel, 0);
        assertEquals(branchDistance, 1.0, 0);
    }
}