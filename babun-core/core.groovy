#!/usr/bin/env groovy
// === MODERNIZED: Groovy 4+/5 requires explicit grab + import for AntBuilder ===
//   In Groovy 3 and earlier, AntBuilder was part of groovy-core and resolved
//   without an import. Groovy 4 split it into the optional `groovy-ant` module.
@Grab(group='org.apache.groovy', module='groovy-ant', version='5.0.6')
import groovy.ant.AntBuilder
// === /MODERNIZED ===
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
    // === MODERNIZED: rename instead of copy (cp -a / AntBuilder / robocopy all failed) ===
    //   Original behavior:
    //     new AntBuilder().copy( todir: "${outputFolder.absolutePath}/cygwin", quiet: true ) {
    //       fileset( dir: "${cygwinFolder.absolutePath}", defaultexcludes:"no" )
    //     }
    //     new AntBuilder().copy( file:"${rootFolder.absolutePath}/target/cygwin.version",
    //                            tofile:"${outputFolder.absolutePath}/cygwin/usr/local/etc/babun/installed/cygwin" )
    //
    //   Why changed: AntBuilder.copy didn't preserve NTFS SYSTEM-bit symlink encoding,
    //   reparse points, or hardlinks the way modern Cygwin needs. Tried robocopy
    //   (/COPY:DAT /SL) — same problem. Tried Cygwin's own cp -a invoked via bash
    //   — Java's Windows ProcessBuilder mangles backslashes in quoted args, so
    //   `cygpath -u 'C:\path'` saw an empty path. A filesystem rename does no
    //   per-file processing so it preserves everything, and is atomic.
    //
    //   Tradeoff: target/babun-cygwin/cygwin/ is gone after this step. A partial
    //   Stage-3 failure requires `groovy build.groovy clean` before re-running.
    File dstDir = new File(outputFolder, "cygwin")
    if (dstDir.exists()) {
        throw new RuntimeException("${dstDir.absolutePath} already exists; run `groovy build.groovy clean` before retrying")
    }
    outputFolder.mkdirs()
    println "Moving cygwin tree: ${cygwinFolder.absolutePath} -> ${dstDir.absolutePath}"
    java.nio.file.Files.move(cygwinFolder.toPath(), dstDir.toPath())

    // The /usr/local/etc/babun/installed/ chain doesn't exist after a fresh
    // Cygwin install — it's babun-owned, populated by plugin install scripts
    // later in Stage 3. We need the chain in place now to write the version marker.
    File versionDst = new File(dstDir, "usr/local/etc/babun/installed/cygwin")
    versionDst.parentFile.mkdirs()
    versionDst.bytes = new File(rootFolder, "target/cygwin.version").bytes
    // === /MODERNIZED ===
}

// -----------------------------------------------------
// TODO - EXTERNALIZE THE INSTALLATION OF THE BABUN CORE
// THIS SHOULD BE A SEPARATE SHELL SCRIPT
// IT WILL ENABLE INSTALLING THE CORE ON OSX!!!
// -----------------------------------------------------
def installCore(File outputFolder, String babunBranch, File rootFolder) {
    // === MODERNIZED: removed rebaseall ===
    //   Original behavior:
    //     executeCmd("${outputFolder.absolutePath}/cygwin/bin/dash.exe -c '/usr/bin/rebaseall'", 5)
    //   Why changed: rebaseall was originally needed on 32-bit Cygwin to avoid
    //   fork() address conflicts. On 64-bit it is rarely needed (huge VA + ASLR)
    //   and was observed to corrupt the cygwin1.dll/libcurl/libssl chain such
    //   that git-remote-https.exe aborted on every HTTPS clone. The end-user's
    //   install.bat still runs rebaseall on the target machine if needed.
    // === /MODERNIZED ===

    // setup bash invoked
    String bash = "${outputFolder.absolutePath}/cygwin/bin/bash.exe -l"

    // === MODERNIZED: copy babun source from local working tree (no GitHub clone) ===
    //   Original behavior:
    //     String sslVerify = "git config --global http.sslverify"
    //     String src = "/usr/local/etc/babun/source"
    //     String clone = "git clone https://github.com/babun/babun.git ${src}"
    //     String checkout = "git --git-dir='${src}/.git' --work-tree='${src}' checkout ${babunBranch}"
    //     executeCmd("${bash} -c \"${sslVerify} 'false'; ${clone}; ${checkout}; ${sslVerify} 'true';\"", 5)
    //
    //   Why changed: clone-from-github made every dev iteration require a commit
    //   + push to GitHub before the build could see the change. Copying the local
    //   working tree means edits flow into the build immediately. AntBuilder.copy
    //   is fine for *adding* fresh files (no Cygwin special attrs in play here);
    //   it was only problematic for the cygwin sysroot itself.
    String dest = "${outputFolder.absolutePath}/cygwin/usr/local/etc/babun/source"
    println "Copying babun source from ${rootFolder.absolutePath} to ${dest}"
    new AntBuilder().copy(todir: dest, overwrite: "true", quiet: true) {
        fileset(dir: "${rootFolder.absolutePath}", defaultexcludes: "no") {
            exclude(name: "target/**")
        }
    }
    // === /MODERNIZED ===

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
