package org.mate.graphs;

import static org.junit.Assert.assertEquals;


import org.junit.Before;
import org.junit.Test;
import org.mate.util.Log;
import org.mate.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.CFGVertex;

public class InterCDGTest {
    private final File RESOURCES = new File("./src/test/java/resources/");
    private final File APK_FILE = new File("./src/test/java/resources/com.zola.bmi.apk");

    private InterCDG cdg;


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
        List<String> traces = new ArrayList<>();
        traces.add("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->11:0");
        traces.add("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->11:1");
        traces.add("Lcom/zola/bmi/BMIMain;->calculateClickHandler(Landroid/view/View;)V->56:0");
        traces.add("Lcom/zola/bmi/BMIMain;->calculateClickHandler(Landroid/view/View;)V->56:1");
        CFGVertex branchVertex = cdg.lookupVertex("Lcom/zola/bmi/BMIMain;->interpretBMI(D)Ljava/lang/String;->if->11");
        assertEquals(cdg.computeBranchDistance(branchVertex, traces), 1, 0);
    }
}