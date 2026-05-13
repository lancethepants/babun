#!/usr/bin/env groovy
@Grab(group='org.apache.groovy', module='groovy-ant', version='5.0.6')
import groovy.ant.AntBuilder
import static java.lang.System.*

execute()
 
def execute() {
    File rootFolder, cygwinFolder, outputFolder
    try {
        checkArguments()
        (rootFolder, cygwinFolder, outputFolder, babunBranch) = initEnvironment()
        copyCygwin(rootFolder, cygwinFolder, outputFolder)
        installCore(outputFolder, babunBranch, rootFolder)
    } catch (Exception ex) {
        error("ERROR: Unexpected error occurred: " + ex + " . Quitting!", true)
        ex.printStackTrace()
        exit(-1)
    }
}

def checkArguments() {
    if (this.args.length != 4) {
        error("Usage: core.groovy <babun_root> <cygwin_folder> <output_folder> <babun_branch>")
        exit(-1)
    }
}

def initEnvironment() {
    File rootFolder = new File(this.args[0]) 
    File cygwinFolder = new File(this.args[1])
    File outputFolder = new File(this.args[2])
    String babunBranch = this.args[3]
    if (!outputFolder.exists()) {
        outputFolder.mkdir()
    }    
    return [rootFolder, cygwinFolder, outputFolder, babunBranch]
}

def copyCygwin(File rootFolder, File cygwinFolder, File outputFolder) {
    // Move (rename) rather than copy the cygwin tree. Every copy approach we tried
    // — AntBuilder.copy, robocopy, even cygwin's own `cp -a` invoked via bash —
    // either dropped Cygwin's NTFS symlink encoding and reparse-point junctions
    // (breaking DLL loading for git-remote-https.exe et al.) or got hijacked by
    // Windows' WSL `bash.exe` App Execution Alias and silently produced nothing.
    // A filesystem-level rename does no per-file processing, preserves everything,
    // and is atomic. The tradeoff: target/babun-cygwin/cygwin/ is gone after this
    // step, so a partial Stage-3 failure requires `clean` before re-running.
    File dstDir = new File(outputFolder, "cygwin")
    if (dstDir.exists()) {
        throw new RuntimeException("${dstDir.absolutePath} already exists; run `groovy build.groovy clean` before retrying")
    }
    outputFolder.mkdirs()
    println "Moving cygwin tree: ${cygwinFolder.absolutePath} -> ${dstDir.absolutePath}"
    java.nio.file.Files.move(cygwinFolder.toPath(), dstDir.toPath())
    println "Move complete; sanity: ${new File(dstDir, "bin/bash.exe").exists() ? "bin/bash.exe present" : "MISSING bin/bash.exe"}"

    File versionDst = new File(dstDir, "usr/local/etc/babun/installed/cygwin")
    versionDst.parentFile.mkdirs()
    versionDst.bytes = new File(rootFolder, "target/cygwin.version").bytes
}

// -----------------------------------------------------
// TODO - EXTERNALIZE THE INSTALLATION OF THE BABUN CORE
// THIS SHOULD BE A SEPARATE SHELL SCRIPT
// IT WILL ENABLE INSTALLING THE CORE ON OSX!!!
// -----------------------------------------------------
def installCore(File outputFolder, String babunBranch, File rootFolder) {
    // Note: rebaseall was historically run here to fix fork() conflicts on 32-bit
    // Cygwin. On 64-bit it is rarely needed and was observed to break libcurl/libssl
    // such that `git-remote-https` aborted on every HTTPS clone. The end-user's
    // install.bat still runs rebaseall on the target machine.

    // setup bash invoked
    String bash = "${outputFolder.absolutePath}/cygwin/bin/bash.exe -l"

    // copy babun source from the local working tree into the cygwin sysroot.
    // this replaces the prior `git clone https://github.com/...` so local edits
    // (including uncommitted ones) flow into the built dist without a push cycle.
    String dest = "${outputFolder.absolutePath}/cygwin/usr/local/etc/babun/source"
    println "Copying babun source from ${rootFolder.absolutePath} to ${dest}"
    new AntBuilder().copy(todir: dest, overwrite: "true", quiet: true) {
        fileset(dir: "${rootFolder.absolutePath}", defaultexcludes: "no") {
            exclude(name: "target/**")
        }
    }
    // Sanity check: show the deployed cacert install.sh so we can verify it matches the working tree
    File deployedCacert = new File("${dest}/babun-core/plugins/cacert/install.sh")
    if (deployedCacert.exists()) {
        println "--- deployed cacert/install.sh (first 6 lines) ---"
        deployedCacert.readLines().take(6).each { println "    ${it}" }
        println "--- end deployed cacert ---"
    } else {
        println "WARNING: deployed cacert/install.sh not found at ${deployedCacert.absolutePath}"
    }
    
    // remove windows new line feeds
    String dos2unix = "find /usr/local/etc/babun/source/babun-core -type f -exec dos2unix {} \\;"
    executeCmd("${bash} -c \"${dos2unix}\"", 5)

    // make installer executable
    String chmod = "find /usr/local/etc/babun/source/babun-core -type f -regex '.*sh' -exec chmod u+x {} \\;"
    executeCmd("${bash} -c \"${chmod}\"", 5)

    // invoke init.sh
    executeCmd("${bash} \"/usr/local/etc/babun/source/babun-core/tools/init.sh\"", 5)

    // run babun installer - yay!
    executeCmd("${bash} \"/usr/local/etc/babun/source/babun-core/plugins/install.sh\"", 5)
}


def executeCmd(String command, int timeout) {
    println "Executing: ${command}"
    def process = command.execute()
    addShutdownHook { process.destroy() }
    process.consumeProcessOutput(out, err)
    process.waitForOrKill(timeout * 60000)
    assert process.exitValue() == 0
}

def error(String message, boolean noPrefix = false) {
    err.println((noPrefix ? "" : "ERROR: ") + message)
}
