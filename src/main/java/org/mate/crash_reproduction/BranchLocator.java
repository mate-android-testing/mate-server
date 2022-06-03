package org.mate.crash_reproduction;

import de.uni_passau.fim.auermich.android_graphs.core.utility.Utility;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.ReferenceType;
import org.jf.dexlib2.builder.BuilderDebugItem;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22t;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.debug.LineNumber;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public List<String> getInstructionForStackTrace(List<String> stackTrace, String packageName) {
        return stackTrace.stream()
                .filter(line -> line.contains(packageName))
                .map(this::getInstructionForStackTraceLine)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    public Stream<String> getTokensForStackTrace(StackTrace stackTrace, String packageName) {
        return stackTrace.getStackTraceAtLines()
                .filter(l -> l.isFromPackage(packageName))
                .filter(line -> line.getFileName().isPresent() && line.getLineNumber().isPresent())
                .flatMap(line -> getTokensFromStackTraceLine(line.getMethodName(), line.getFileName().get(), line.getLineNumber().get()));
    }

    public Set<String> getInstructionForStackTraceLine(String stackTraceLine) {
        Pattern pattern = Pattern.compile("at (.+)\\.(.+)\\((.+):(.+)\\)");
        Matcher matcher = pattern.matcher(stackTraceLine.trim());

        if (matcher.matches()) {
            String methodName = matcher.group(2);
            String sourceFile = matcher.group(3);
            int lineInFile = Integer.parseInt(matcher.group(4));
            return getInstructionForStackTraceLine(methodName, sourceFile, lineInFile);
        } else {
            throw new IllegalArgumentException("Invalid stack trace line syntax! Expecting 'at the.class.Name.methodName(File:LineNumber)'");
        }
    }

    public Set<String> getInstructionForStackTraceLine(String methodName, String sourceFile, int lineInFile) {
        Set<String> targets = new HashSet<>();
        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (sourceFile.equals(classDef.getSourceFile())) {
                    for (Method method : classDef.getMethods()) {
                        if (method.toString().contains(methodName) && method.getImplementation() != null) {
                            MutableMethodImplementation mutableMethodImplementation = new MutableMethodImplementation(method.getImplementation());
                            List<BuilderInstruction> instructions = mutableMethodImplementation.getInstructions();
                            for (int i = 0; i < instructions.size(); i++) {
                                BuilderInstruction instruction = instructions.get(i);
                                if (getLineNumber(instruction.getLocation().getDebugItems()).map(line -> lineInFile == line).orElse(false)) {
                                    targets.add(closestBranch(instructions, i).map(instr -> map(method, instr)).orElseGet(() -> map(method)));
                                }
                            }
                        }
                    }
                }
            }
        }

        return targets;
    }

    public Stream<String> getTokensFromStackTraceLine(String methodName, String sourceFile, int lineInFile) {
        return getInstructionsForStackTraceLine(dexFiles, methodName, sourceFile, lineInFile).stream()
                .flatMap(this::getTokensFromInstruction);
    }

    private Stream<String> getTokensFromInstruction(Instruction instruction) {
        if (instruction instanceof ReferenceInstruction) {
            ReferenceInstruction referenceInstruction = (ReferenceInstruction) instruction;

            if (referenceInstruction.getReferenceType() == ReferenceType.STRING) {
                return Arrays.stream(referenceInstruction.getReference().toString().split("[-_\\s]"));
            }
        }
        return Stream.empty();
    }

    public static Set<Instruction> getInstructionsForStackTraceLine(List<DexFile> dexFiles, String methodName, String sourceFile, int lineInFile) {
        return getInstructionsInMethod(dexFiles, methodName, sourceFile, lineInFile).second;
    }

    public static Pair<Method, Set<Instruction>> getInstructionsInMethod(List<DexFile> dexFiles, String methodName, String sourceFile, int lineInFile) {
        Method stackTraceMethod = null;

        Set<Instruction> result = new HashSet<>();
        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                if (sourceFile.equals(classDef.getSourceFile())) {
                    for (Method method : classDef.getMethods()) {
                        if (method.toString().contains(methodName) && method.getImplementation() != null) {
                            MutableMethodImplementation mutableMethodImplementation = new MutableMethodImplementation(method.getImplementation());
                            List<BuilderInstruction> instructions = mutableMethodImplementation.getInstructions();
                            for (BuilderInstruction instruction : instructions) {
                                if (getLineNumber(instruction.getLocation().getDebugItems()).map(line -> lineInFile == line).orElse(false)) {
                                    result.add(instruction);
                                    stackTraceMethod = method;
                                }
                            }
                        }
                    }
                }
            }
        }

        return new Pair<>(stackTraceMethod, result);
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

    private Optional<BuilderInstruction> closestBranch(List<BuilderInstruction> mutableMethodImplementation, int index) {
        for (int i = index; i >= 0; i--) {
            BuilderInstruction instruction = mutableMethodImplementation.get(i);
            if (instruction instanceof BuilderInstruction21t
                    || instruction instanceof BuilderInstruction22t) {
                return Optional.of(instruction);
            }
        }

        return Optional.empty();
    }

    private String map(Method method, BuilderInstruction instruction) {
        return method.toString() + "->" + instruction.getLocation().getIndex();
    }

    private String map(Method method) {
        return method.toString() + "->exit";
    }

    public static class Pair<F, S> {
        public final F first;
        public final S second;

        private Pair(F first, S second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Pair<?, ?> pair = (Pair<?, ?>) o;
            return Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }
}
