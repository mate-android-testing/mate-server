package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.app.APK;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Component;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ComponentUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ManyToManyShortestPathsAlgorithm;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Represents a call tree where vertices represent methods and edges describe invocations from one method to the other.
 */
public class CallTree implements Graph<CallTreeVertex, CallTreeEdge> {

    /**
     * The employed shortest path algorithm. For individual vertices the bi-directional dijkstra seems to be the fastest
     * option, while for resolving the shortest paths between many vertices, the CH many-to-many shortest path algorithm
     * appears to be the best option.
     */
    private final ManyToManyShortestPathsAlgorithm<CallTreeVertex, CallTreeEdge> shortestPathAlgorithm;

    /**
     * The interCFG from which the call tree is finally derived.
     */
    private final InterCFG interCFG;

    /**
     * The core call tree.
     */
    private final de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTree callTree;

    /**
     * The set of discovered components, e.g. activities.
     */
    private final Set<Component> components;

    /**
     * The APK file.
     */
    private final APK apk;

    /**
     * Constructs a new call tree with the given properties.
     *
     * @param apkPath The path to the APK file.
     * @param excludeARTClasses Whether to exclude ART classes.
     * @param resolveOnlyAUTClasses Whether to only resolve classes belonging to the AUT package.
     * @param appsDir The path to the apps dir.
     * @param packageName The package name of the AUT.
     */
    public CallTree(File apkPath, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        this.callTree = GraphUtils.constructCallTree(apkPath, excludeARTClasses, resolveOnlyAUTClasses);
        this.interCFG = new InterCFG(callTree.getInterCFG(), appsDir, packageName);
        shortestPathAlgorithm = callTree.initCHManyToManyShortestPathAlgorithm();
        this.components = callTree.getInterCFG().getComponents();
        this.apk = callTree.getInterCFG().getApk();
    }

    /**
     * Returns the APK file.
     *
     * @return Returns the APK file.
     */
    public APK getApk() {
        return apk;
    }

    /**
     * Returns the interCFG from which the call tree was derived.
     *
     * @return Returns the interCFG.
     */
    public InterCFG getInterCFG() {
        return interCFG;
    }

    /**
     * Returns the shortest path starting at the given start vertex through the given stop points if possible.
     *
     * @param startVertex The given start vertex.
     * @param stops The given stop points.
     * @return Returns the shortest path starting at the given start vertex through the given stop points if such path exists.
     */
    public Optional<GraphPath<CallTreeVertex, CallTreeEdge>> getShortestPathWithStops(final CallTreeVertex startVertex,
                                                                                      final List<CallTreeVertex> stops) {
        return callTree.getShortestPathWithStops(startVertex, stops);
    }

    /**
     * Retrieves a possible shortest path through the given stop points.
     *
     * @param stops The given stop points.
     * @return Returns the shortest path through the given stop points if such path exists.
     */
    public Optional<GraphPath<CallTreeVertex, CallTreeEdge>> getShortestPathWithStops(final List<CallTreeVertex> stops) {
        return callTree.getShortestPathWithStops(stops);
    }

    /**
     * Converts the call tree to a dot file.
     *
     * @param output The path to the dot file.
     * @param methodsToHighlight The methods (call tree vertices) that should be highlighted.
     */
    public void toDot(final File output, final Map<String, String> methodsToHighlight) {
        callTree.toDot(output, methodsToHighlight);
    }

    /**
     * Looks up a component by name and type.
     *
     * @param componentName The component name.
     * @param componentType The component type.
     * @return Returns the component if matching name and type.
     */
    public Optional<Component> getComponentByNameAndType(final String componentName, final ComponentType componentType) {

        final Optional<Component> component = ComponentUtils.getComponentByName(components, componentName);

        if (component.isPresent() && component.get().getComponentType() == componentType) {
            return component;
        } else {
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return callTree.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAppName() {
        return interCFG.getAppName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<CallTreeVertex> getVertices() {
        return new ArrayList<>(callTree.getVertices());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BiFunction<CallTreeVertex, CallTreeVertex, Integer> getDistances(Set<CallTreeVertex> sources, Set<CallTreeVertex> targets) {
        final var distances
                = shortestPathAlgorithm.getManyToManyPaths(sources, targets);
        return (s, t) -> {
            final var path = distances.getPath(s, t);
            return path != null ? path.getLength() : -1;
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDistance(CallTreeVertex source, CallTreeVertex target) {
        return callTree.getShortestDistance(source, target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallTreeVertex lookupVertex(String trace) {
        return callTree.lookUpVertex(trace);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReachable(CallTreeVertex vertex) {
        return shortestPathAlgorithm.getPath(callTree.getRoot(), vertex) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void draw(File outputPath) {
        callTree.drawGraph(outputPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void draw(File outputPath, Set<CallTreeVertex> visitedVertices, Set<CallTreeVertex> targets) {
        callTree.drawGraph(outputPath, visitedVertices, targets);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<CallTreeEdge> getOutgoingEdges(CallTreeVertex vertex) {
        return callTree.getOutgoingEdges(vertex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<CallTreeEdge> getIncomingEdges(CallTreeVertex vertex) {
        return callTree.getIncomingEdges(vertex);
    }
}
