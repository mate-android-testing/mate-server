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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;

public class InterCDGTest {
    private final File RESOURCES = new File("./src/test/java/resources/");
    private final File APK_FILE = new File("./src/test/java/resources/com.zola.bmi.apk");
    private final File TRACES_FILE = new File("./src/test/java/resources/com.zola.bmi/trace1.txt");

    private InterCDG cdg;
    private List<String> traces;
    Set<CFGVertex> covered;

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

        cdg = new InterCDG(APK_FILE, true, false, true, RESOURCES.toPath(), "com.zola.bmi");
        cdg.initTraceToVertexCache();
        covered = this.cdg.getCoveredVertices(new HashSet<>(traces));
    }

    @Test
    public void testComputeApproachLevel() {
        CFGVertex target = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->82");
        Set<CFGVertex> visited = new HashSet<>();
        visited.add(cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->calculateClickHandler(Landroid/view/View;)V->if->13"));
        CFGVertex expectedShortest = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->if->11");
        visited.add(expectedShortest);
        Pair<CFGVertex, Integer> result = cdg.computeApproachLevel(target, visited);
        assertEquals(result.fst(), expectedShortest);
        assertEquals(result.snd(), Integer.valueOf(4));
    }

    @Test
    public void computeBranchDistance() {
        CFGVertex branchVertex = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->calculateClickHandler(Landroid/view/View;)V->if->70");
        assertEquals(cdg.computeBranchDistance(branchVertex, traces), 1, 0);
    }

    @Test
    public void computeApproachLevelAndBranchDistance() {
        CFGVertex target = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->76");
        Pair<CFGVertex, Integer> result = cdg.computeApproachLevel(target, covered);
        int approachLevel = result.snd();
        double branchDistance = cdg.computeBranchDistance(result.fst(), traces);
        assertEquals(approachLevel, 0);
        assertEquals(branchDistance, 1.0, 0);
    }
}