package org.mate.graphs;

import de.uni_passau.fim.auermich.android_graphs.core.app.APK;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.Component;
import de.uni_passau.fim.auermich.android_graphs.core.app.components.ComponentType;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTree;
import de.uni_passau.fim.auermich.android_graphs.core.calltrees.CallTreeVertex;
import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BasicStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.BlockStatement;
import de.uni_passau.fim.auermich.android_graphs.core.statements.Statement;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassHierarchy;
import de.uni_passau.fim.auermich.android_graphs.core.utility.ClassUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.GraphUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.Utility;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.analysis.AnalyzedInstruction;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.mate.crash_reproduction.AtStackTraceLine;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InterCFG extends CFG {
    private final CallTree callTree;
    private final Set<Component> components;
    private final ClassHierarchy classHierarchy;
    private final APK apk;

    public static void main(String[] args) throws IOException {
        Log.registerLogger(new Log());
        Path appDir = Path.of("/home/dominik/IdeaProjects/mate-commander/apps");
        Path plainAppsDir = Path.of("/home/dominik/IdeaProjects/mate-commander/plain-apps/");
        Collection<String> packageNames =
//                List.of("com.mitzuli");
                Arrays.stream(plainAppsDir.toFile().listFiles()).map(File::getName).map(n -> n.replace(".apk", "")).sorted().collect(Collectors.toList());

        for (String packageName : packageNames) {
            if (packageName.contains("fantastisch")) {
                continue;
            }
            Log.println("Looking at: " + packageName);
            File apkPath = plainAppsDir.resolve(packageName + ".apk").toFile();
            File stackTraceFile = appDir.resolve(packageName).resolve("stack_trace.txt").toFile();

            StackTrace stackTrace = StackTraceParser.parse(Files.lines(stackTraceFile.toPath()).collect(Collectors.toList()));
            var inter = new InterCFG(apkPath, true, true, true, appDir, packageName);
            var targetComponents = inter.getTargetComponents(stackTrace.getStackTraceAtLines().filter(l -> l.isFromPackage(packageName)).collect(Collectors.toList()), ComponentType.ACTIVITY, ComponentType.FRAGMENT);

            try (var filePrinter = new PrintWriter(new FileWriter("targets.txt", true))) {
                filePrinter.println(packageName);
                targetComponents.forEach(filePrinter::println);
                filePrinter.println();
            }

//        var callTree = new CallTree((de.uni_passau.fim.auermich.android_graphs.core.graphs.cfg.InterCFG) inter.baseCFG);
//        callTree.toDot(new File("calltree.dot"));
//
//            BranchLocator branchLocator = new BranchLocator(dexFiles(apkPath));
//            Set<String> instructionTokens = branchLocator.getTokensForStackTrace(stackTrace, packageName).collect(Collectors.toSet());
//            Set<String> fuzzyTokens = stackTrace.getFuzzyTokens(packageName);
//            Set<String> userInputTokens = stackTrace.getUserTokens();
//            Log.println("Instruction Tokens: " + String.join(", ", instructionTokens));
//            Log.println("Fuzzy Tokens: " + String.join(", ", fuzzyTokens));
//            Log.println("User input Tokens: " + String.join(", ", userInputTokens));

//            List<String> targets = branchLocator.getInstructionForStackTrace(stackTrace.getAtLines(), packageName);
//        List<Vertex> targetVertex = targets.stream()
//                .map(inter::lookupVertex)
//                .collect(Collectors.toList());
//
//        inter.draw(new File("."));
//        List<?> paths = targetVertex.stream()
//                .map(v -> callTree.getShortestPath(v.getMethod()).map(GraphPath::getVertexList))
//                .collect(Collectors.toList());
//        Collections.reverse(targetVertex);
//        Optional<?> path = callTree.getShortestPathWithStops(targetVertex.stream().map(Vertex::getMethod).map(CallTreeVertex::new).collect(Collectors.toList()))
//                .map(GraphPath::getVertexList);
//        callTree.toDot(new File("calltree.dot"));
            System.out.println("");
        }
        System.out.println("");
    }

    private static List<DexFile> dexFiles(File apkPath) {
        MultiDexContainer<? extends DexBackedDexFile> apk = null;

        try {
            apk = DexFileFactory.loadDexContainer(apkPath, Utility.API_OPCODE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<DexFile> dexFiles = new ArrayList<>();
        List<String> dexEntries = new ArrayList<>();

        try {
            dexEntries = apk.getDexEntryNames();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (String dexEntry : dexEntries) {
            try {
                dexFiles.add(apk.getEntry(dexEntry).getDexFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return dexFiles;
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
        String startMethod = BranchLocator.getInstructionsForLine(apk.getDexFiles(), line).orElseThrow().getX().toString();

        return callTree.getMethodCallers(new CallTreeVertex(startMethod), methodSatisfies);
    }

    public Optional<Component> getComponentOfClass(String clazz, ComponentType componentType) {
        return components.stream().filter(c -> c.getName().equals(clazz))
                .filter(c -> c.getComponentType() == componentType)
                .findAny();
    }

    public Optional<Vertex> findClosestBranch(Method method, BuilderInstruction builderInstruction) {
//        AnalyzedInstruction analyzedInstruction = getAnalyzedInstruction(method, builderInstruction);



        return findClosestBranch(findVertexByInstruction(method, builderInstruction));
    }

    public Optional<Vertex> findClosestBranch(Vertex vertex) {
        return baseCFG.getVertices().stream()
                .filter(v -> v.getMethod().equals(vertex.getMethod())
                        && (v.isIfVertex() || v.isEntryVertex()))
                .min(Comparator.comparingInt(branch -> baseCFG.getShortestDistance(branch, vertex)));
    }

    private AnalyzedInstruction getAnalyzedInstruction(Method method, BuilderInstruction builderInstruction) {
        return statementToAnalyzedInstructions(findVertexByInstruction(method, builderInstruction).getStatement())
                .filter(a -> a.getInstructionIndex() == builderInstruction.getLocation().getIndex())
                .findAny()
                .orElseThrow();
    }

    private Stream<AnalyzedInstruction> statementToAnalyzedInstructions(Statement statement) {
        if (statement instanceof BlockStatement) {
            return ((BlockStatement) statement).getStatements().stream()
                    .flatMap(this::statementToAnalyzedInstructions);
        } else if (statement instanceof BasicStatement) {
            return Stream.of(((BasicStatement) statement).getInstruction());
        } else {
            return Stream.empty();
        }
    }

    public Vertex findVertexByInstruction(Method method, BuilderInstruction builderInstruction) {
        Set<Vertex> vertices = getVertices().stream()
                .filter(vertex -> vertex.containsInstruction(method.toString(), builderInstruction.getLocation().getIndex()))
                .collect(Collectors.toSet());

        if (vertices.size() == 1) {
            return vertices.stream().findAny().orElseThrow();
        } else {
            throw new NoSuchElementException();
        }
    }
}
