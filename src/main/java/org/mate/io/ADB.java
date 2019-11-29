package org.mate.io;

import java.util.List;

public class ADB {

    public static boolean isWin = System.getProperty("os.name")
            .startsWith("Windows");

    public static List<String> runCommand(String cmd){
        return ProcessRunner.runProcess(isWin, cmd);
    }
}
