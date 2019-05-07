# MATE-Server
The MATE-Server is part of the MATE project for automated Android testing. The server
runs on the host machine and is responsible for some functionality that can not be performed
from within the instrumentation tests.

## How to run the MATE-Server

### Java
JDK-1.8 or newer is needed to build MATE-Server. The PATH variable needs to be set
correctly so that java and javac can be executed by the gradle-wrapper.

### ADB
ADB needs to be installed and the PATH needs to be set accordingly. **Warning:** If you use
Android Studio make sure to that the ADB version supplied by Android Studio is used
instead of the version installed via the package manager. Otherwise the adb daemon used
by Android Studio will conflict with the adb version of your system. One way to achieve this
is by adding this to the .profile file in your home directory (create the file if it does not
exist):

```
PATH=~/Android/Sdk/platform-tools:$PATH
```

You will need to log out and log back in again in order for the changes to take place.

### Scripts and Jacoco
Currently MATE-Server some functionality (mostly coverage related) depends on the python scripts
that are located in the `scripts` subdirectory. Therefore a working Python 3 installation is
needed. Furthermore the python scripts need to be placed in the working of MATE-Server. For the
coverage calculation the jacococli is also needed in the working directory of MATE-Server with
the following path: `bin/jacococli.jar`.

### Installing and running MATE

#### a) IntelliJ IDEA (for Developers)

Open IntelliJ. Select "Check out project from Version Control" and click through the wizard
(use git with url https://github.com/mate-android-testing/mate-server.git). When asked
if you would like to open settings.gradle say yes. Traverse the project directory to
`src->main->java->org.mate` right click Server2 and click on `Run 'Server2.main()'`.

#### b) Gradle

Clone the git repository

```
git clone https://github.com/mate-android-testing/mate-server.git
```

Navigate to the project folder

```
cd mate-server
```

Build jar file with all dependencies using the gradle-wrapper

```
./gradlew fatJar
```

Run MATE-Server

```
java -jar build/libs/mate-server-all-1.0-SNAPSHOT.jar
```
