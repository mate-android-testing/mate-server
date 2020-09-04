package org.mate.io;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class ProcessRunner {
    private ProcessRunner() {}

    public static boolean isWin = System.getProperty("os.name")
            .startsWith("Windows");

    public static List<String> runProcess(String... cmd){
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
            }
            return lines;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
