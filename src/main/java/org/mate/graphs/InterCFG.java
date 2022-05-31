package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.app.APK;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Component;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTree;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Edge;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassHierarchy;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.UsageSearch;
import org.apache.commons.lang3.tuple.Pair;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jgrapht.GraphPath;
import org.mate.crash_reproduction.BranchLocator;
import org.mate.crash_reproduction.StackTrace;
import org.mate.crash_reproduction.StackTraceParser;
import org.mate.util.Log;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InterCFG extends CFG {
    private final CallTree callTree;
    private final Set<Component> components;
    private final ClassHierarchy classHierarchy;
    private final APK apk;

    public static void main(String[] args) throws IOException {
        Log.registerLogger(new Log());
        String packageName = "com.liato.bankdroid";
        Path appDir = Path.of("/home/dominik/IdeaProjects/mate-commander/apps");
        File stackTraceFile = new File(appDir.resolve(packageName).toFile(), "stack_trace.txt");

        StackTrace stackTrace = StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()));
        var inter = new InterCFG(new File("/home/dominik/IdeaProjects/mate-commander/plain-apps/com.liato.bankdroid.apk"), true, true, true, appDir, packageName);
        var callTree = new CallTree((de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.InterCFG) inter.baseCFG);

        List<Vertex> targetVertex = new BranchLocator(inter.apk.getDexFiles()).getInstructionForStackTrace(stackTrace.getAtLines(), packageName).stream()
                .map(inter::lookupVertex)
                .collect(Collectors.toList());

        inter.draw(new File("."));
        List<?> paths = targetVertex.stream()
                .map(v -> callTree.getShortestPath(v.getMethod()).map(GraphPath::getVertexList))
                .collect(Collectors.toList());
        Collections.reverse(targetVertex);
        Optional<?> path = callTree.getShortestPathWithStops(targetVertex.stream().map(Vertex::getMethod).map(CallTreeVertex::new).collect(Collectors.toList()))
                .map(GraphPath::getVertexList);
        System.out.println("");
    }

    public InterCFG(File apkPath, boolean useBasicBlocks, boolean excludeARTClasses, boolean resolveOnlyAUTClasses,
                    Path appsDir, String packageName) {
        super(GraphUtils.constructInterCFG(apkPath, useBasicBlocks, excludeARTClasses, resolveOnlyAUTClasses),
                appsDir, packageName);
        try {
            Field componentsField = de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.InterCFG.class.getDeclaredField("components");
            componentsField.setAccessible(true);
            components = (Set<Component>) componentsField.get(baseCFG);

            Field apkField = de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.InterCFG.class.getDeclaredField("apk");
            apkField.setAccessible(true);
            apk = (APK) apkField.get(baseCFG);

            Field classHierarchyField = de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.InterCFG.class.getDeclaredField("classHierarchy");
            classHierarchyField.setAccessible(true);
            classHierarchy = (ClassHierarchy) classHierarchyField.get(baseCFG);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }

        callTree = new CallTree((de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.InterCFG) baseCFG);
    }

    public CallTree getCallTree() {
        return callTree;
    }

    public Set<String> getTargetComponents(List<String> stackTrace, ComponentType... componentTypes) {
        Set<String> targetActivities = getTargets(stackTrace, stackTraceLine -> getTargetComponentsByClassUsage(stackTraceLine, componentTypes));

        Log.println("Target {" + Arrays.stream(componentTypes).map(ComponentType::name).collect(Collectors.joining(" or ")) + "} are: [" + String.join(", ", targetActivities) + "]");
        return targetActivities;
    }

    private Set<String> getTargets(List<String> stackTrace, Function<String, Set<String>> stackTraceLineToUsages) {
        return stackTrace.stream()
                .map(stackTraceLineToUsages)
                .filter(Predicate.not(Set::isEmpty))
                .reduce(this::lenientComponentIntersection)
                .map(this::keepMostSpecific)
                .map(set -> set.stream().map(ClassUtils::dottedClassName).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    public Set<String> keepMostSpecific(Set<String> components) {
        Set<String> result = new HashSet<>(components);

        for (String component : components) {
            classHierarchy.getSuperClasses(component).forEach(result::remove);
        }

        return result;
    }

    public Set<String> lenientComponentIntersection(Set<String> components1, Set<String> components2) {
        return intersection(components1, components2,
                (comp1, comp2) -> comp1.equals(comp2)
                        || classHierarchy.getSuperClasses(comp1).contains(comp2)
                        || classHierarchy.getSuperClasses(comp2).contains(comp1));
    }

    public <T> Set<T> intersection(Set<T> set1, Set<T> set2, BiPredicate<T, T> equalityRelation) {
        Set<T> result = new HashSet<>();

        for (T elem1 : set1) {
            for (T elem2 : set2) {
                if (equalityRelation.test(elem1, elem2)) {
                    result.add(elem1);
                    result.add(elem2);
                }
            }
        }

        return result;
    }

    private <T> Set<T> goUntilSatisfied(T start, Function<T, Stream<T>> childGetter, Predicate<T> predicate) {
        Queue<T> workQueue = new LinkedList<>();
        workQueue.add(start);
        Set<T> satisfied = new HashSet<>();
        Set<T> seen = new HashSet<>();

        while (!workQueue.isEmpty()) {
            T current = workQueue.poll();
            seen.add(current);

            if (predicate.test(current)) {
                satisfied.add(current);
            } else {
                childGetter.apply(current)
                        .filter(Predicate.not(seen::contains))
                        .forEach(workQueue::add);
            }
        }

        return satisfied;
    }

    public Set<String> getTargetComponentsByClassUsage(String line, ComponentType... componentTypes) {
        return getTargetsByClassUsage(line, clazz -> Arrays.stream(componentTypes).anyMatch(componentType -> getComponentOfClass(clazz, componentType).isPresent()));
    }

    public Set<String> getTargetsByClassUsage(String line, Predicate<String> satisfies) {
        Pattern pattern = Pattern.compile("at (.+)\\.\\S+\\(.+\\)");
        Matcher matcher = pattern.matcher(line);

        if (matcher.matches()) {
            String className = matcher.group(1);

            return goUntilSatisfied(
                    ClassUtils.convertDottedClassName(className),
                    clazz -> UsageSearch.findClassUsages(apk, clazz).stream().map(u -> u.getClazz().toString()),
                    satisfies::test
            );
        } else {
            throw new IllegalStateException();
        }
    }

    private Optional<Method> findMethod(String dottedClassName, String methodName) {
        String slashedClassName = ClassUtils.convertDottedClassName(dottedClassName);
        String methodPrefix = slashedClassName + "->" + methodName + "(";

        for (DexFile dexFile : apk.getDexFiles()) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (classDef.toString().equals(slashedClassName)) {
                    for (Method method : classDef.getMethods()) {
                        if (method.toString().startsWith(methodPrefix)) {
                            return Optional.of(method);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public Set<String> getTargetActivities(Vertex vertex) {
        Queue<Vertex> vertices = new LinkedList<>();
        vertices.add(vertex);
        Set<Vertex> seenVertices = new HashSet<>();
        Set<Component> targetActivities = new HashSet<>();

        Log.println("Starting while loop");
        Set<Pair<String, String>> pairs = new HashSet<>();
        Field typeField;
        try {
            typeField = Vertex.class.getDeclaredField("type");
            typeField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        while (!vertices.isEmpty()) {
            Vertex source = vertices.poll();
            Log.println("Handling: " + source.getMethod() + " remaining: " + vertices.size());
            seenVertices.add(source);

            Optional<Component> sourceActivity = getActivityOfVertex(source);

            if (sourceActivity.isPresent()) {
                targetActivities.add(sourceActivity.get());
            } else {
                Set<Edge> edges = baseCFG.getIncomingEdges(source);
                edges.forEach(e -> {
                    try {
                        pairs.add(Pair.of(((Enum) typeField.get(e.getSource())).name(), ((Enum) typeField.get(e.getTarget())).name()));
                    } catch (IllegalAccessException ex) {
                        ex.printStackTrace();
                    }
                });
                vertices.addAll(edges.stream().map(Edge::getSource).collect(Collectors.toList()));
                vertices.removeIf(Vertex::isExitVertex); // Not a real source vertex
                vertices.removeIf(seenVertices::contains);
            }
        }
        Log.println("Done with while loop");

        return targetActivities.stream().map(Component::getName).collect(Collectors.toSet());
    }

    public Optional<Component> getActivityOfVertex(Vertex vertex) {
        return getVertexClass(vertex).flatMap(this::getActivityOfClass);
    }

    public Optional<Component> getActivityOfClass(String clazz) {
        return getComponentOfClass(clazz, ComponentType.ACTIVITY);
    }

    public Optional<Component> getFragmentOfClass(String clazz) {
        return getComponentOfClass(clazz, ComponentType.FRAGMENT);
    }

    public Optional<Component> getComponentOfClass(String clazz, ComponentType componentType) {
        return components.stream().filter(c -> c.getName().equals(clazz))
                .filter(c -> c.getComponentType() == componentType)
                .findAny();
    }

    public Optional<String> getVertexClass(Vertex vertex) {
        if (vertex.getMethod().equals("global")) {
            return Optional.empty();
        }

        List<Pattern> patterns = List.of(
                Pattern.compile("(L.+;)->.+"),
                Pattern.compile("\\S+ (L.+;)$")
        );

        return Optional.of(matchAny(vertex.getMethod(), patterns).orElseThrow(() -> new IllegalArgumentException("Cannot get vertex class for '" + vertex.getMethod() + "'")));
    }

    private Optional<String> matchAny(String input, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(input);

            if (matcher.matches()) {
                return Optional.of(matcher.group(1));
            }
        }

        return Optional.empty();
    }
}
