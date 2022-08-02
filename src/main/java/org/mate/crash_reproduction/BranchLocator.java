package org.mate.crash_reproduction;

import de.uni_passau.fim.auermich.android_graphs.core.graphs.Vertex;
import de.uni_passau.fim.auermich.android_graphs.core.utility.MenuUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.MethodUtils;
import de.uni_passau.fim.auermich.android_graphs.core.utility.Tuple;
import de.uni_passau.fim.auermich.android_graphs.core.utility.Utility;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.builder.BuilderDebugItem;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.debug.LineNumber;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.util.MethodUtil;
import org.mate.graphs.InterCFG;
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class BranchLocator {
    private final List<DexFile> dexFiles;

    public BranchLocator(File apkPath) throws IOException {
        this(BranchLocator.fromAPK(apkPath));
    }

    public BranchLocator(List<DexFile> dexFiles) {
        this.dexFiles = dexFiles;
    }

    public static List<DexFile> fromAPK(File apkPath) throws IOException {
        MultiDexContainer<? extends DexFile> apk = DexFileFactory.loadDexContainer(apkPath, Utility.API_OPCODE);

        List<DexFile> dexFiles = new ArrayList<>();

        List<String> dexEntries = apk.getDexEntryNames();

        for (String dexEntry : dexEntries) {
            dexFiles.add(apk.getEntry(dexEntry).getDexFile());
        }

        return dexFiles;
    }

    public Map<AtStackTraceLine, Set<Vertex>> getTargetTracesForStackTrace(List<AtStackTraceLine> stackTrace,
                                                                           InterCFG interCFG,
                                                                           String packageName) {
        return getLastConsecutiveLines(stackTrace, packageName).stream()
                .collect(Collectors.toMap(Function.identity(), line -> getTargetTracesForStackTraceLine(line, interCFG)));
    }

    public Set<Vertex> getTargetTracesForStackTraceLine(AtStackTraceLine line, InterCFG interCFG) {
        var result = getInstructionsForLine(line).orElseThrow();

        return result.getY().stream()
                .map(instruction -> interCFG.findVertexByInstruction(result.getX(), instruction))
                .collect(Collectors.toSet());
    }

    public Set<String> getRequiredConstructorCalls(AtStackTraceLine line) {
        return getInstructionsForLine(line)
                .stream()
                .flatMap(methodAndInstructions -> Stream.concat(
                        getRequiredConstructorCalls(methodAndInstructions.getX().toString()), // Required constructors to reach the method containing the instruction
                        methodAndInstructions.getY().stream().flatMap(this::getRequiredConstructorCalls)
                ))
                .collect(Collectors.toSet());
    }

    private Stream<String> getRequiredConstructorCalls(BuilderInstruction instruction) {
        if (instruction.getOpcode().referenceType == ReferenceType.METHOD) {
            String methodName = ((ReferenceInstruction) instruction).getReference().toString();

            return getRequiredConstructorCalls(methodName);
        } else {
            // TODO add more cases (e.g. when accessing a field)
            return Stream.empty();
        }
    }

    private Stream<String> getRequiredConstructorCalls(String methodName) {
        String className = MethodUtils.getClassName(methodName);
        Method targetMethod = null;
        List<Method> constructors = new LinkedList<>();

        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (classDef.toString().equals(className)) {
                    for (Method method : classDef.getMethods()) {
                        if (method.toString().equals(methodName)) {
                            targetMethod = method;
                        }

                        if (MethodUtil.isConstructor(method)) {
                            constructors.add(method);
                        }
                    }
                }
            }
        }

        if (targetMethod == null) {
            Log.printWarning("Was not able to find method " + methodName);
            return Stream.empty();
        }

        return Stream.concat(
                // This is an over approximation, since it's always possible to pass null as a value
                // thus not every parameter might be actually required to call the method
                targetMethod.getParameterTypes().stream().map(Objects::toString).flatMap(this::getConstructors),
                MethodUtil.isStatic(targetMethod)
                        ? Stream.of(className + "-><clinit>()V") // if the method is static we only need the static constructor
                        : constructors.stream().map(Objects::toString)
        );
    }

    private Stream<String> getConstructors(String className) {
        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (classDef.toString().equals(className)) {
                    return StreamSupport.stream(classDef.getMethods().spliterator(), false)
                            .filter(MethodUtil::isConstructor)
                            .map(Method::toString);
                }
            }
        }

        Log.printWarning("Was not able to find constructor for " + className);
        return Stream.empty();
    }

    public List<AtStackTraceLine> getLastConsecutiveLines(List<AtStackTraceLine> stackTrace, String packageName) {
        List<AtStackTraceLine> instructions = new LinkedList<>();
        boolean reachedPackage = false;

        for (int i = stackTrace.size() - 1; i >= 0; i--) {
            var line = stackTrace.get(i);
            if (line.isFromPackage(packageName)) {
                reachedPackage = true;
                instructions.add(0, line);
            } else if (reachedPackage) {
                return instructions;
            }
        }

        return instructions;
    }

    public Stream<String> getTokensForStackTrace(StackTrace stackTrace, String packageName) {
        return stackTrace.getStackTraceAtLines()
                .filter(l -> l.isFromPackage(packageName))
                .filter(line -> line.getFileName().isPresent() && line.getLineNumber().isPresent())
                .flatMap(this::getTokensFromStackTraceLine);
    }

    public Optional<Tuple<Method, Set<BuilderInstruction>>> getInstructionsForLine(AtStackTraceLine stackTraceLine) {
        return BranchLocator.getInstructionsForLine(dexFiles, stackTraceLine);
    }

    public static Optional<Tuple<Method, Set<BuilderInstruction>>> getInstructionsForLine(Collection<DexFile> dexFiles, AtStackTraceLine stackTraceLine) {
        if (stackTraceLine.getFileName().isEmpty() || stackTraceLine.getLineNumber().isEmpty()) {
            return Optional.empty();
        }

        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (stackTraceLine.getFileName().get().equals(classDef.getSourceFile())) {
                    for (Method method : classDef.getMethods()) {
                        if (method.toString().contains(stackTraceLine.getMethodName()) && method.getImplementation() != null) {
                            Set<BuilderInstruction> instructionsAtLine = new HashSet<>();

                            MutableMethodImplementation mutableMethodImplementation = new MutableMethodImplementation(method.getImplementation());
                            List<BuilderInstruction> instructions = mutableMethodImplementation.getInstructions();

                            for (BuilderInstruction instruction : instructions) {
                                // TODO figure out how to get liner number of all instructions...
                                // Currently only some instructions hold their line number as a debug item
                                if (getLineNumber(instruction.getLocation().getDebugItems()).map(lineNumber -> lineNumber.equals(stackTraceLine.getLineNumber().get())).orElse(false)) {
                                    instructionsAtLine.add(instruction);
                                }
                            }

                            if (!instructionsAtLine.isEmpty()) {
                                return Optional.of(new Tuple<>(method, instructionsAtLine));
                            }
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    public Stream<String> getTokensFromStackTraceLine(AtStackTraceLine stackTraceLine) {
        var result = getInstructionsForLine(stackTraceLine).orElseThrow();
        return result.getY().stream()
                .flatMap(instruction -> Stream.concat(
                        BranchLocator.getTokensFromInstruction(instruction),
                        MenuUtils.getMenuItemStringId(instruction, result.getX(), dexFiles).stream()
                                .flatMap(id -> Arrays.stream(id.split("_"))) // TODO resolve string resource id to actual translation
                ));
    }

    private static Stream<String> getTokensFromInstruction(Instruction instruction) {
        if (instruction instanceof ReferenceInstruction) {
            ReferenceInstruction referenceInstruction = (ReferenceInstruction) instruction;

            if (referenceInstruction.getReferenceType() == ReferenceType.STRING) {
                return Arrays.stream(referenceInstruction.getReference().toString().split("[-_\\s]"));
            } else if (referenceInstruction.getReferenceType() == ReferenceType.METHOD) {
                Set<String> ignoreMethods = Set.of("<init>", "doInBackground");
                String methodName = MethodUtils.getMethodName(referenceInstruction.getReference().toString()).split("\\(")[0];
                return ignoreMethods.contains(methodName) ? Stream.empty() : TokenUtil.splitCamelCase(methodName)
                        .map(String::toLowerCase)
                        .filter(method -> !ignoreMethods.contains(method));
            }
        }
        return Stream.empty();
    }

    private static Optional<Integer> getLineNumber(Set<BuilderDebugItem> debugItemSet) {
        return debugItemSet.stream().map(a -> {
            if (a instanceof LineNumber) {
                return Optional.of((LineNumber) a);
            } else {
                return Optional.<LineNumber>empty();
            }
        }).flatMap(Optional::stream)
                .findAny().map(LineNumber::getLineNumber);
    }
}
