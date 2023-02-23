package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.app.APK;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Component;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTree;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.calltree.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassHierarchy;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.iface.Method;
import org.mate.crash_reproduction.AtStackTraceLine;
import org.mate.crash_reproduction.CrashReproductionUtil;
import org.mate.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class InterCFG extends CFG {
    private final CallTree callTree;
    private final Set<Component> components;
    private final ClassHierarchy classHierarchy;
    private final APK apk;

    public APK getApk() {
        return apk;
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

    public Set<String> getTargetComponents(List<AtStackTraceLine> stackTrace, ComponentType... componentTypes) {
        Set<String> targetActivities = getTargets(stackTrace, stackTraceLine -> getTargetComponentsByClassUsage(stackTraceLine, componentTypes));

        Log.println("Target {" + Arrays.stream(componentTypes).map(ComponentType::name).collect(Collectors.joining(" or ")) + "} are: [" + String.join(", ", targetActivities) + "]");
        return targetActivities;
    }

    private Set<String> getTargets(List<AtStackTraceLine> stackTrace, Function<AtStackTraceLine, Set<String>> stackTraceLineToUsages) {
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

    public Set<String> getTargetComponentsByClassUsage(AtStackTraceLine line, ComponentType... componentTypes) {
        return getTargetsByClassUsage(line, method -> Arrays.stream(componentTypes).anyMatch(componentType -> getComponentOfClass(method.getClassName(), componentType).isPresent()))
                .stream().map(CallTreeVertex::getClassName).collect(Collectors.toSet());
    }

    public Set<CallTreeVertex> getTargetsByClassUsage(AtStackTraceLine line, Predicate<CallTreeVertex> methodSatisfies) {
        String startMethod = CrashReproductionUtil.getInstructionsForLine(apk.getDexFiles(), line).orElseThrow().getX().toString();

        return callTree.getMethodCallers(new CallTreeVertex(startMethod), methodSatisfies);
    }

    public Optional<Component> getComponentOfClass(String clazz, ComponentType componentType) {
        return components.stream().filter(c -> c.getName().equals(clazz))
                .filter(c -> c.getComponentType() == componentType)
                .findAny();
    }

    public Vertex findVertexByInstruction(Method method, BuilderInstruction builderInstruction) {
        Set<Vertex> vertices = getVertices().stream()
                .filter(vertex -> vertex.containsInstruction(method.toString(),
                        builderInstruction.getLocation().getIndex()))
                .collect(Collectors.toSet());

        if (vertices.size() == 1) {
            return vertices.stream().findAny().orElseThrow();
        } else {
            throw new NoSuchElementException();
        }
    }
}
