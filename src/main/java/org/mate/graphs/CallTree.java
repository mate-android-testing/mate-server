package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.app.APK;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Component;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeEdge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassHierarchy;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ComponentUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ManyToManyShortestPathsAlgorithm;
import org.mate.crash_reproduction.AtStackTraceLine;
import org.mate.crash_reproduction.CrashReproductionUtil;
import org.mate.util.Log;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
     * Provides access to the discovered class hierarchy, e.g. one can retrieve the super/sub classes of a given class.
     */
    private final ClassHierarchy classHierarchy;

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
        this.classHierarchy = callTree.getInterCFG().getClassHierarchy();
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
     * Retrieves the set of target components.
     *
     * @param stackTraceLines The stack trace lines.
     * @param componentTypes The allowed component types.
     * @return Returns the target components.
     */
    public Set<String> getTargetComponents(final List<AtStackTraceLine> stackTraceLines,
                                           final ComponentType... componentTypes) {

        // Retrieve the target components for each stack trace line.
        final Set<String> targetComponents
                = getTargetComponents(stackTraceLines,
                stackTraceLine -> getTargetMethodCallersByComponentType(stackTraceLine, componentTypes));

        Log.println("Target {" + Arrays.stream(componentTypes).map(ComponentType::name)
                .collect(Collectors.joining(" or ")) + "} are: [" + String.join(", ", targetComponents) + "]");
        return targetComponents;
    }

    /**
     * Retrieves the target components from the given stack trace lines.
     *
     * @param stackTraceLines The given stack trace lines.
     * @param stackTraceLineToTargetMethodCallers A function that derives for each stack trace line the components that
     *                                              call the target method encoded in the stack trace line.
     * @return Returns the target components for the given stack trace lines.
     */
    private Set<String> getTargetComponents(final List<AtStackTraceLine> stackTraceLines,
                                            final Function<AtStackTraceLine, Set<String>> stackTraceLineToTargetMethodCallers) {
        return stackTraceLines.stream()
                // Retrieve all components that invoke (transitively) the method encoded in the stack trace line.
                .map(stackTraceLineToTargetMethodCallers)
                // Certain methods seen in the stack trace are not invoked by one of the specified component types.
                .filter(Predicate.not(Set::isEmpty))
                // Perform an intersection that respects the super class relation between the components.
                .reduce(this::lenientComponentIntersection)
                // Remove common base classes, e.g. an abstract activity class.
                .map(this::keepMostSpecific)
                // Map entries to dotted class name as seen in the supplied stack trace.
                .map(set -> set.stream().map(ClassUtils::dottedClassName).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * Removes from the given components all common super classes.
     *
     * @param components The given components.
     * @return Returns the given components without the common super classes.
     */
    private Set<String> keepMostSpecific(final Set<String> components) {

        final Set<String> result = new HashSet<>(components);

        for (final String component : components) {
            classHierarchy.getSuperClasses(component).forEach(result::remove);
        }

        return result;
    }

    /**
     * Performs an intersection between the given two sets of components using a special equality relation function that
     * maintains a component in the resulting set if a component is identical to the other component or if a component
     * represents a super class of the other component.
     *
     * @param components1 The first set of components.
     * @param components2 The second set of components.
     * @return Returns the intersection of the given two sets.
     */
    private Set<String> lenientComponentIntersection(final Set<String> components1, final Set<String> components2) {
        return intersection(components1, components2,
                (comp1, comp2) -> comp1.equals(comp2)
                        || classHierarchy.getSuperClasses(comp1).contains(comp2)
                        || classHierarchy.getSuperClasses(comp2).contains(comp1));
    }

    /**
     * Performs an intersection between the given two sets that uses the given equality relation to determine equality
     * between two elements.
     *
     * @param set1 The first set.
     * @param set2 The second set.
     * @param equalityRelation The equality relation function to determine equality between two elements.
     * @param <T> The element type of the given sets.
     * @return Returns the intersection of the given two sets.
     */
    private <T> Set<T> intersection(final Set<T> set1, final Set<T> set2, final BiPredicate<T, T> equalityRelation) {

        final Set<T> interSection = new HashSet<>();

        for (T elem1 : set1) {
            for (T elem2 : set2) {
                if (equalityRelation.test(elem1, elem2)) {
                    interSection.add(elem1);
                    interSection.add(elem2);
                }
            }
        }

        return interSection;
    }

    /**
     * Retrieves the component names that match one of the given component types and invoke (transitively)
     * the target method encoded in the stack trace line.
     *
     * @param stackTraceLine The given stack trace line.
     * @param componentTypes The allowed component types.
     * @return Returns the specified components that invoke the target method.
     */
    private Set<String> getTargetMethodCallersByComponentType(final AtStackTraceLine stackTraceLine,
                                                             final ComponentType... componentTypes) {
        return getTargetMethodCallers(stackTraceLine,
                // A method caller needs to be a component, e.g. activity, of one of the specified types.
                methodCaller -> Arrays.stream(componentTypes)
                .anyMatch(componentType ->
                        getComponentByNameAndType(methodCaller.getClassName(), componentType).isPresent()))
                .stream()
                .map(CallTreeVertex::getClassName).collect(Collectors.toSet());
    }

    /**
     * Retrieves all call tree vertices that (transitively) invoke the target method and satisfy the given predicate.
     *
     * @param stackTraceLine The stack trace line encoding the target method.
     * @param methodCallerPredicate A predicate that a method caller of the target method needs to satisfy.
     * @return Returns all call tree vertices that invoke the target method.
     */
    private Set<CallTreeVertex> getTargetMethodCallers(final AtStackTraceLine stackTraceLine,
                                                      final Predicate<CallTreeVertex> methodCallerPredicate) {

        // Retrieve the method that is matching the method name encoded in the stack trace line.
        final String targetMethod = CrashReproductionUtil.getInstructionsForLine(apk.getDexFiles(), stackTraceLine)
                .orElseThrow().getX().toString();

        return callTree.getMethodCallers(new CallTreeVertex(targetMethod), methodCallerPredicate);
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
    public List<CallTreeVertex> getBranchVertices() {
        throw new UnsupportedOperationException("No branch vertices available in the call tree!");
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
