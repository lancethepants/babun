#!/usr/bin/env groovy
// === MODERNIZED: Groovy 4+/5 requires explicit grab + import for AntBuilder ===
//   In Groovy 3 and earlier, AntBuilder was part of groovy-core. Groovy 4 split
//   it into the optional `groovy-ant` module.
@Grab(group='org.apache.groovy', module='groovy-ant', version='5.0.6')
import groovy.ant.AntBuilder
// === /MODERNIZED ===
import static java.lang.System.*

execute()

def execute() {
    File repoFolder, inputFolder, outputFolder, cygwinFolder, pkgsFile
    boolean downloadOnly
    try {
        checkArguments()
        (repoFolder, inputFolder, outputFolder, cygwinFolder, pkgsFile, downloadOnly) = initEnvironment()
        // install cygwin
        File cygwinInstaller = downloadCygwinInstaller(outputFolder)
        if(downloadOnly) {
            println "downloadOnly flag set to true - Cygwin installation skipped.";
            return
        }
        installCygwin(cygwinInstaller, repoFolder, cygwinFolder, pkgsFile)
        cygwinInstaller.delete()

        // handle symlinks
        copySymlinksScripts(inputFolder, cygwinFolder)
        findSymlinks(cygwinFolder)

        // === MODERNIZED: write cygwin.version marker ===
        //   Original behavior: this file was committed in a separate
        //   github.com/babun/babun-cygwin/ repo and never created during build.
        //   That repo is dead. We now derive the real version (e.g. 3.6.9-1)
        //   from the cygwin package's `version:` line in setup.ini.
        //   Consumed by babun-core to populate installed/cygwin marker.
        writeCygwinVersion(outputFolder, repoFolder)
        // === /MODERNIZED ===
    } catch (Exception ex) {
        error("ERROR: Unexpected error occurred: " + ex + " . Quitting!", true)
        ex.printStackTrace()
        exit(-1)
    }
}

def checkArguments() {
    if (this.args.length != 5) {
        error("Usage: cygwin.groovy <repo_folder> <input_folder> <output_folder> <pkgs_file> <download_only>")
        exit(-1)
    }
}

def initEnvironment() {
    File repoFolder = new File(this.args[0])
    File inputFolder = new File(this.args[1])
    File outputFolder = new File(this.args[2])
    File pkgsFile = new File(this.args[3]) 
    boolean downloadOnly =  Boolean.parseBoolean(this.args[4])
    if (!outputFolder.exists()) {
        // === MODERNIZED: mkdir -> mkdirs (same fix as in packages.groovy) ===
        //   Original:  outputFolder.mkdir()
        outputFolder.mkdirs()
        // === /MODERNIZED ===
    }
    File cygwinFolder = new File(outputFolder, "cygwin")
    // === MODERNIZED: mkdir -> mkdirs ===
    //   Original:  cygwinFolder.mkdir()
    cygwinFolder.mkdirs()
    // === /MODERNIZED ===
    return [repoFolder, inputFolder, outputFolder, cygwinFolder, pkgsFile, downloadOnly]
}

def downloadCygwinInstaller(File outputFolder) {
    // === MODERNIZED: setup-x86.exe -> setup-x86_64.exe, http -> https ===
    //   Original:
    //     File cygwinInstaller = new File(outputFolder, "setup-x86.exe")
    //     ...
    //     cygwinInstaller << "http://cygwin.com/setup-x86.exe".toURL()
    File cygwinInstaller = new File(outputFolder, "setup-x86_64.exe")
    if(!cygwinInstaller.exists()) {
        println "Downloading Cygwin installer"
        use(FileBinaryCategory) {
            cygwinInstaller << "https://cygwin.com/setup-x86_64.exe".toURL()
        }
    } else {
        println "Cygwin installer alread exists, skipping the download!";
    }
    // === /MODERNIZED ===

    return cygwinInstaller
}

def installCygwin(File cygwinInstaller, File repoFolder, File cygwinFolder, File pkgsFile) {    
    println "Installing cygwin"
    String pkgs = pkgsFile.text.trim().replaceAll("(\\s)+", ",")    
    println "Packages to install: ${pkgs}"
    // === MODERNIZED: added --no-admin and --no-verify ===
    //   --no-admin: prevents UAC elevation. Without it, setup.exe re-launches
    //   itself elevated and the original process returns immediately while the
    //   install is still running. The build then races ahead to symlinks_find
    //   and finds no bash.exe.
    //   --no-verify: we don't fetch .sig signature files alongside packages,
    //   so signature verification on setup.ini would fail.
    String installCommand = "\"${cygwinInstaller.absolutePath}\" " +
            "--quiet-mode " +
            "--no-admin " +
            "--no-verify " +
            "--local-install " +
            "--local-package-dir \"${repoFolder.absolutePath}\" " +
            "--root \"${cygwinFolder.absolutePath}\" " +
            "--no-shortcuts " +
            "--no-startmenu " +
            "--no-desktop " +
            "--packages " + pkgs
    // === /MODERNIZED ===
    println installCommand
    executeCmd(installCommand, 10)
}

def copySymlinksScripts(File inputFolder, File cygwinFolder) {
    new AntBuilder().copy(todir: "${cygwinFolder.absolutePath}/etc/postinstall", quiet: true) {
        fileset(dir: "${inputFolder.absolutePath}/symlinks", defaultexcludes:"no")
    }    
}

// === MODERNIZED: new function — write cygwin.version marker ===
//   This file used to live in github.com/babun/babun-cygwin/cygwin.version
//   (a separate repo, now dead). We parse the installed cygwin package's
//   `version:` from setup.ini and write the real version to target/cygwin.version
//   for core.groovy to copy into the dist tree as the installed-cygwin marker.
//
//   We also mirror the version to the REPO-ROOT `cygwin.version` file. That
//   file is what check.sh fetches via raw.githubusercontent.com to determine
//   "newest" available Cygwin version. By keeping it in sync with what was
//   actually built, end-user `babun check` correctly compares their installed
//   version against the version this fork last shipped. If the cygwin upstream
//   has bumped between builds, this file changes too — show up as a git diff
//   and commit/push so users see [OUTDATED] correctly.
def writeCygwinVersion(File outputFolder, File repoFolder) {
    String version = "0.0.0"
    File setupIni = new File(repoFolder, "x86_64/setup.ini")
    if (setupIni.exists()) {
        String cygwinEntry = setupIni.text.split("(?=@ )").find { it.startsWith("@ cygwin\n") }
        String versionLine = cygwinEntry?.split("\n")?.find { it.startsWith("version:") }
        if (versionLine) {
            // setup.ini's version: format is "X.Y.Z-R" (e.g. "3.6.9-1"). babun's
            // get_version_as_number in check.sh only parses bare X.Y.Z, so we
            // strip the "-R" build tag and any arch suffix.
            String raw = versionLine.replace("version:", "").trim()
            def m = raw =~ /^(\d+\.\d+\.\d+)/
            version = m ? m[0][1] : raw
        }
    }
    // target/cygwin.version — consumed by core.groovy, becomes the dist's
    // installed marker after extraction on the end-user machine.
    File buildVersionFile = new File(outputFolder.parentFile, "cygwin.version")
    buildVersionFile.text = "${version}\n"
    println "Wrote ${buildVersionFile.absolutePath} (cygwin ${version})"

    // <repo-root>/cygwin.version — fetched by check.sh from raw.githubusercontent
    // as the "newest" reference. Sync to keep "outdated" detection accurate.
    // outputFolder.parentFile = target/, .parentFile = repo root.
    File repoVersionFile = new File(outputFolder.parentFile.parentFile, "cygwin.version")
    String existing = repoVersionFile.exists() ? repoVersionFile.text.trim() : ""
    if (existing != version) {
        repoVersionFile.text = "${version}\n"
        println "Updated ${repoVersionFile.absolutePath} (was [${existing}], now [${version}]) — remember to commit + push"
    } else {
        println "${repoVersionFile.absolutePath} already at ${version} — no change"
    }
}
// === /MODERNIZED ===

def findSymlinks(File cygwinFolder) {
    String symlinksFindScript = "/etc/postinstall/symlinks_find.sh"
    String findSymlinksCmd = "${cygwinFolder.absolutePath}/bin/bash.exe --norc --noprofile \"${symlinksFindScript}\""
    executeCmd(findSymlinksCmd, 10)
    new File(cygwinFolder, symlinksFindScript).renameTo(new File(cygwinFolder, symlinksFindScript + ".done"))
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

class FileBinaryCategory {
    def static leftShift(File file, URL url) {
        url.withInputStream { is ->
            file.withOutputStream { os ->
                def bs = new BufferedOutputStream(os)
                bs << is
            }
        }
    }
}
