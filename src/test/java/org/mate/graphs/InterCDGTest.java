package org.mate.graphs;

import static org.junit.Assert.assertEquals;


import org.junit.Before;
import org.junit.Test;
import org.mate.endpoints.GraphEndpoint;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;

public class InterCDGTest {
    private final File RESOURCES = new File("./src/test/java/resources/");
    private final File APK_FILE = new File("./src/test/java/resources/android.bignerdranch.com.apk");
    private final File TRACES_FILE = new File("./src/test/java/resources/android.bignerdranch.com/trace.txt");

    private InterCDG cdg;
    private List<String> traces;
    Set<Vertex> covered;

    private List<String> readTraceFile(File traceFile) {
        try (Stream<String> stream = Files.lines(traceFile.toPath(), StandardCharsets.UTF_8)) {
            return stream.collect(Collectors.toList());
        } catch (IOException e) {
            Log.println("Reading traces.txt failed!");
            throw new IllegalStateException(e);
        }
    }


    @Before
    public void setup() {
        Log.registerLogger(new Log());
        traces = this.readTraceFile(TRACES_FILE);

        cdg = new InterCDG(APK_FILE, true, true, true, RESOURCES.toPath(), "android.bignerdranch.com");
        cdg.initTraceToVertexCache();
        covered = new HashSet<>(GraphEndpoint.mapTracesToVertices(cdg, traces));
        // cdg.draw(RESOURCES, covered, new HashSet<>());
    }

    @Test
    public void computeApproachLevelAndBranchDistanceIfStatement() {
        CFGVertex target = cdg.lookupVertex("Landroid/bignerdranch/com/MainActivity;->ifFunction(I)V->17");
        Pair<CFGVertex, Integer> approachLevelPair = cdg.computeApproachLevel(target, covered);

        Vertex expectedMissedVertex = cdg.lookupVertex("Landroid/bignerdranch/com/MainActivity;->ifFunction(I)V->if->2");
        Vertex missedVertex = approachLevelPair.fst();
        assertEquals(expectedMissedVertex, missedVertex);

        int approachLevel = approachLevelPair.snd();
        assertEquals(approachLevel, 0);

        double branchDistance = cdg.computeBranchDistance(approachLevelPair.fst(), traces);
        assertEquals(branchDistance, 0.75, 0);
    }

    @Test
    public void computeApproachLevelAndBranchDistanceSwitchStatement() {
        CFGVertex target = cdg.lookupVertex("Landroid/bignerdranch/com/MainActivity;->switchFunction(Ljava/lang/String;)V->9");
        Pair<CFGVertex, Integer> approachLevelPair = cdg.computeApproachLevel(target, covered);

        Vertex expectedMissedVertex = cdg.lookupVertex("Landroid/bignerdranch/com/MainActivity;->switchFunction(Ljava/lang/String;)V->6");
        Vertex missedVertex = approachLevelPair.fst();
        assertEquals(expectedMissedVertex, missedVertex);

        int approachLevel = approachLevelPair.snd();
        assertEquals(approachLevel, 1);

        double branchDistance = cdg.computeBranchDistance(approachLevelPair.fst(), traces);
        assertEquals(branchDistance, 0.5, 0);
    }
}