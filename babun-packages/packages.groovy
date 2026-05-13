#!/usr/bin/env groovy
import static java.lang.System.*
import static groovy.io.FileType.*

execute()

def execute() {
    File confFolder, outputFolder
    String setupVersion
    try {
        checkArguments()
        (confFolder, outputFolder, setupVersion) = initEnvironment()
        // === MODERNIZED: x86 -> x86_64 ===
        //   Original:  downloadPackages(confFolder, outputFolder, "x86")
        //   Original:  copyPackagesListToTarget(confFolder, outputFolder, "x86")
        downloadPackages(confFolder, outputFolder, "x86_64")
        copyPackagesListToTarget(confFolder, outputFolder, "x86_64")
        // === /MODERNIZED ===
    } catch (Exception ex) {
        error("Unexpected error occurred: " + ex + " . Quitting!")
        ex.printStackTrace()
        exit(-1)
    }
}

def checkArguments() {
    if (this.args.length != 2) {
        error("Usage: packages.groovy <conf_folder> <output_folder>", true)
        exit(-1)
    }
}

def initEnvironment() {
    File confFolder = new File(this.args[0])
    File outputFolder = new File(this.args[1])
    if (!outputFolder.exists()) {
        // === MODERNIZED: mkdir -> mkdirs ===
        //   Original:  outputFolder.mkdir()
        //   Why: mkdir creates only one directory level. target/ may not exist
        //   on a fresh build, in which case mkdir on target/babun-packages
        //   fails silently. mkdirs creates the whole parent chain.
        outputFolder.mkdirs()
        // === /MODERNIZED ===
    }
    return [confFolder, outputFolder]
}

def copyPackagesListToTarget(File confFolder, File outputFolder, String bitVersion) {
    File packagesFile = new File(confFolder, "cygwin.${bitVersion}.packages")
    File outputFile = new File(outputFolder, "cygwin.${bitVersion}.packages")
    outputFile.createNewFile()
    outputFile << packagesFile.text
}

def downloadPackages(File confFolder, File outputFolder, String bitVersion) {
    File packagesFile = new File(confFolder, "cygwin.${bitVersion}.packages")
    def rootPackages = packagesFile.readLines().findAll() { it }
    def repositories = new File(confFolder, "cygwin.repositories").readLines().findAll() { it }
    def processed = [] as Set
    // === MODERNIZED: auto-include all Base-category packages from setup.ini ===
    //   The original only walked deps from the explicit packages list. Modern
    //   Cygwin's setup.exe enforces a "Base" set (crypto-policies, ca-certificates,
    //   dash, tzdata, _autorebase, etc.) that must be available locally for any
    //   installation to succeed — even when only requesting a subset of packages.
    //   Without this we got `Can't happen. No packagemeta for base` and 0 tasks
    //   solved. We discover the Base set from setup.ini once per build.
    boolean addedBase = false
    // === /MODERNIZED ===
    for (repo in repositories) {
        String setupIni = downloadSetupIni(repo, bitVersion, outputFolder)
        // === MODERNIZED: see above ===
        if (!addedBase) {
            def basePackages = findBasePackages(setupIni)
            println "Found ${basePackages.size()} Base-category packages; adding to download set"
            for (String basePkg : basePackages) {
                if (!rootPackages.contains(basePkg)) rootPackages.add(basePkg)
            }
            addedBase = true
        }
        // === /MODERNIZED ===
        for (String rootPkg : rootPackages) {
            if (processed.contains(rootPkg.trim())) continue
            def processedInStep = downloadRootPackage(repo, setupIni, rootPkg.trim(), processed, outputFolder)
            processed.addAll(processedInStep)
        }
        rootPackages.removeAll(processed)
        if (rootPackages.isEmpty()) {
            return
        }
    }
    if (!rootPackages.isEmpty()) {
        error("Could not download the following ${rootPackages}! Quitting!")
        exit(-1)
    }
}

// === MODERNIZED: download setup.ini directly via Java URL (no wget) ===
//   Original behavior:
//     def downloadSetupIni(String repository, String bitVersion, File outputFolder) {
//         println "Downloading [setup.ini] from repository [${repository}]"
//         String setupIniUrl = "${repository}/${bitVersion}/setup.ini"
//         String downloadSetupIni = "wget -l 2 -r -np -q --cut-dirs=3 -P " +
//             outputFolder.getAbsolutePath() + " " + setupIniUrl
//         executeCmd(downloadSetupIni, 5)
//         String setupIniContent = setupIniUrl.toURL().text
//         return setupIniContent
//     }
//
//   Why changed: the bundled `wget.exe` is from 2014 and unlikely to support
//   modern TLS. Worse, the original used `wget -r --cut-dirs=3` which depended
//   on each mirror's URL depth — `mirrors.kernel.org/sourceware/cygwin/` has
//   2 path levels before /x86_64/, others have 0 or 1. Result was packages
//   landing in a mirror-specific subdirectory under target/babun-packages/
//   instead of the canonical /x86_64/release/<pkg>/ layout setup.exe expects,
//   producing "0 tasks" at install time. Direct URL fetching uses Java's
//   built-in modern TLS and lets us control output paths precisely.
def downloadSetupIni(String repository, String bitVersion, File outputFolder) {
    println "Downloading [setup.ini] from repository [${repository}]"
    String setupIniUrl = "${repository}/${bitVersion}/setup.ini"
    File setupIniFile = new File(outputFolder, "${bitVersion}/setup.ini")
    setupIniFile.parentFile.mkdirs()
    new URL(setupIniUrl).withInputStream { is ->
        setupIniFile.withOutputStream { os -> os << is }
    }
    return setupIniFile.text
}
// === /MODERNIZED ===

def downloadRootPackage(String repo, String setupIni, String rootPkg, Set<String> processed, File outputFolder) {
    def processedInStep = [] as Set
    println "Processing top-level package [$rootPkg]"
    def packagesToProcess = [] as Set
    try {
        buildPackageDependencyTree(setupIni, rootPkg, packagesToProcess)
        for (String pkg : packagesToProcess) {
            if (processed.contains(pkg) || processedInStep.contains(pkg)) continue
            String pkgInfo = parsePackageInfo(setupIni, pkg)
            String pkgPath = parsePackagePath(pkgInfo)
            if (pkgPath) {
                println "  Downloading package [$pkg]"
                if (downloadPackage(repo, pkgPath, outputFolder)) {
                    processedInStep.add(pkg)
                }
            } else if (pkgInfo) {
                // packages doesn't have binary file
                processedInStep.add(pkg)
            } else {
                println "  Cannot find package [$pkg] in the repository"
                processedInStep = [] as Set // reset as the tree could not be fetched
                break;
            }
        }
    } catch (Exception ex) {
        error("Could not download dependency tree for [$rootPkg]")
        ex.printStackTrace()
        processedInStep = [] as Set
    }
    processedInStep
}

// === MODERNIZED: skip virtual/missing deps silently (was: throw) ===
//   Original behavior: if a dep wasn't in setup.ini, the function threw
//   RuntimeException("Cannot find dependencies of [${pkgName}]"). That bubbled
//   up to downloadRootPackage which logged "Could not download dependency tree".
//
//   Why changed: modern Cygwin setup.ini uses virtual deps (_windows, _python,
//   etc.) that aren't real downloadable packages. They mark environment
//   constraints. The throw made *every* top-level package with such a transitive
//   dep fail to download. We now silently skip un-resolvable names. Top-level
//   packages from the explicit list are still detected via the downloader's
//   own "Could not download" path if they're missing.
def buildPackageDependencyTree(String setupIni, String pkgName, Set<String> result) {
    String pkgInfo = parsePackageInfo(setupIni, pkgName)
    if (!pkgInfo) {
        return
    }
    result.add(pkgName)
    String[] deps = parsePackageRequires(pkgInfo)
    for (String dep : deps) {
        String name = dep.trim()
        if (name && !result.contains(name)) {
            buildPackageDependencyTree(setupIni, name, result)
        }
    }
}
// === /MODERNIZED ===

// === MODERNIZED: parse modern `depends2:` field in addition to legacy `requires:` ===
//   Original behavior:
//     def parsePackageRequires(String pkgInfo) {
//         String requires = pkgInfo?.split("\n")?.find() { it.startsWith("requires:") }
//         return requires?.replace("requires:", "")?.trim()?.split("\\s")
//     }
//
//   Why changed: modern Cygwin setup.ini uses `depends2:` (comma-separated, may
//   include version constraints like "libiconv2 (>= 1.16)") in addition to or
//   instead of `requires:`. vim-minimal and others declare libiconv2 ONLY via
//   depends2, so the original parser missed it and setup.exe rejected the
//   install with "nothing provides libiconv2". We now union both fields,
//   strip version constraints, and dedupe.
def parsePackageRequires(String pkgInfo) {
    if (!pkgInfo) return new String[0]
    def lines = pkgInfo.split("\n")
    def deps = [] as Set
    String depends2 = lines.find { it.startsWith("depends2:") }
    if (depends2) {
        depends2.replace("depends2:", "").trim().split("\\s*,\\s*").each {
            String name = it.replaceAll(/\s*\(.*\)\s*/, "").trim()
            if (name) deps.add(name)
        }
    }
    String requires = lines.find { it.startsWith("requires:") }
    if (requires) {
        requires.replace("requires:", "").trim().split("\\s+").each {
            if (it.trim()) deps.add(it.trim())
        }
    }
    return deps.toArray(new String[0])
}
// === /MODERNIZED ===

def parsePackageInfo(String setupIni, String packageName) {
    return setupIni?.split("(?=@ )")?.find() { it.contains("@ ${packageName}") }
}

// === MODERNIZED: new helper — extract every package tagged `category: Base` ===
//   Modern Cygwin setup.exe enforces a Base set that must be available locally.
//   We discover them dynamically from setup.ini so we don't have to hard-code
//   names that drift as Cygwin evolves.
def findBasePackages(String setupIni) {
    def basePackages = [] as Set
    for (String entry : setupIni.split("(?=@ )")) {
        def lines = entry.split("\n")
        if (lines.length < 2) continue
        def firstLine = lines[0]
        if (!firstLine.startsWith("@ ")) continue
        def categoryLine = lines.find { it.startsWith("category:") }
        if (categoryLine && categoryLine.split("\\s+").contains("Base")) {
            basePackages.add(firstLine.substring(2).trim())
        }
    }
    return basePackages
}
// === /MODERNIZED ===

def parsePackagePath(String pkgInfo) {
    String version = pkgInfo?.split("\n")?.find() { it.startsWith("install:") }
    String[] tokens = version?.replace("install:", "")?.trim()?.split("\\s")
    return tokens?.length > 0 ? tokens[0] : null
}

// === MODERNIZED: download via Java URL (no wget) — see downloadSetupIni ===
//   Original:
//     def downloadPackage(String repositoryUrl, String packagePath, File outputFolder) {
//         String packageUrl = repositoryUrl + packagePath
//         String downloadCommand = "wget -l 2 -r -np -q --cut-dirs=3 -P " +
//             outputFolder.getAbsolutePath() + " " + packageUrl
//         if (executeCmd(downloadCommand, 5) != 0) {
//             println "Could not download " + packageUrl
//             return false
//         }
//         return true
//     }
//
//   The packagePath comes straight from setup.ini's `install:` field and
//   always starts with `<arch>/release/...` — saving it relative to outputFolder
//   produces the canonical Cygwin local-mirror layout that setup.exe expects.
def downloadPackage(String repositoryUrl, String packagePath, File outputFolder) {
    String packageUrl = repositoryUrl + packagePath
    File pkgFile = new File(outputFolder, packagePath)
    try {
        pkgFile.parentFile.mkdirs()
        new URL(packageUrl).withInputStream { is ->
            pkgFile.withOutputStream { os -> os << is }
        }
        return true
    } catch (Exception ex) {
        println "Could not download " + packageUrl + ": " + ex.message
        return false
    }
}
// === /MODERNIZED ===

int executeCmd(String command, int timeout) {
    def process = command.execute()
    addShutdownHook { process.destroy() }
    process.consumeProcessOutput(out, err)
    process.waitForOrKill(timeout * 60000)
    return process.exitValue()
}

def error(String message, boolean noPrefix = false) {
    err.println((noPrefix ? "" : "ERROR: ") + message)
}
