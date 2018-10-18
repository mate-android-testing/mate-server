package org.mate;

import java.util.List;

/**
 * Created by marceloeler on 21/09/18.
 */
public class ADB {

    public static boolean isWin;

    public static List<String> runCommand(String cmd){
        return ProcessRunner.runProcess(isWin, cmd);
    }
}
