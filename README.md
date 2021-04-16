# MATE-Server
The MATE-Server is part of the MATE project for automated Android testing. The server
runs on the host machine and is responsible for some functionality that can not be performed
from within the instrumentation tests.

## How to run the MATE-Server

### Java
JDK-1.11 or newer is needed to build MATE-Server. The PATH variable needs to be set
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

### Additional configurations

You can provide further configurations via a so-called `mate-server.properties` file.
For instance, you can specify the location of the `apps` directory or the port that is used
for the communication between `MATE` and `MATE-Server`:

```
apps_dir=apps
# port 0 assigns a random port
port=0
```

The file need to be placed in the current working directory.

### Installing and running MATE

#### a) IntelliJ IDEA (for Developers)

Open IntelliJ. Select "Check out project from Version Control" and click through the wizard
(use git with url https://github.com/mate-android-testing/mate-server.git). When asked
if you would like to open settings.gradle say yes. Traverse the project directory to
`src->main->java->org.mate` right click Server and click on `Run 'Server.main()'`.

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
java -jar build/libs/mate-server.jar
```