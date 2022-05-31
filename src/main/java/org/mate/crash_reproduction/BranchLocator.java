package org.mate.crash_reproduction;

import de.uni_passau.fim.auermich.android_graphs.core.utility.Utility;
import org.jf.dexlib2.DexFileFactory;
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
import org.mate.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
        Log.println("getInstructionForStackTraceline(1)");
        return stackTrace.stream()
                .filter(line -> line.contains(packageName))
                .map(this::getInstructionForStackTraceLine)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    public Set<String> getInstructionForStackTraceLine(String stackTraceLine) {
        Log.println("getInstructionForStackTraceline(2)");
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
        Log.println("getInstructionForStackTraceline(3)");
        Set<String> targets = new HashSet<>();
        for (DexFile dexFile : dexFiles) {
            for (ClassDef classDef : dexFile.getClasses()) {
                Log.println("Looking at " + classDef);
                if (sourceFile.equals(classDef.getSourceFile())) {
                    for (Method method : classDef.getMethods()) {
                        Log.println("Looking at " + method);
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

        Log.println("Found target");
        return targets;
    }

    private Optional<Integer> getLineNumber(Set<BuilderDebugItem> debugItemSet) {
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
}
