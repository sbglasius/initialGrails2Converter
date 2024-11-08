#!/usr/bin/env groovy
@Grapes([
        @Grab(group = 'org.codehaus.groovy', module = 'groovy-cli-picocli', version = '3.0.23'),
        @Grab('info.picocli:picocli-groovy:4.2.0')
])
@GrabConfig(systemClassLoader = true)
import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor

def cli = new CliBuilder(usage: "./${this.class.getSimpleName()}.groovy <directory>")
cli.with {
    header = "'directory' is the source directory update integration-tests. If not given, the process stops"
}

//noinspection GroovyAssignabilityCheck
OptionAccessor options = cli.parse(args)
List<String> arguments = options.arguments()

if (options.h || arguments.size() != 1) {
    cli.usage()
    System.exit(0)
}

def directory = new File(arguments[0]).absoluteFile

if (!directory.exists() || !directory.isDirectory()) {
    println("${directory.absolutePath} does not exist")
    System.exit(-1)
}

def IGNORE_IMPORTS = [
//        'import grails.web.taglib.exceptions.GrailsTagException',
]

static List<String> extractMocks(String line, String prefix) {
    String mocks = line - prefix - '(' - ')' - '[' - ']'
    return mocks.split(',').collect { it.trim() }
}

directory.eachFileRecurse { File file ->
    if (file.directory) return

    println "File: ${file.absolutePath - directory.absolutePath}"
    String testFor = ''
    List<Integer> toDelete = []
    List<String> extraImports = ['grails.gorm.transactions.Rollback', 'grails.testing.mixin.integration.Integration']
    List<String> extraAnnotations = ['@Integration', '@Rollback']
    Integer importLine = 0
    Integer classLine = 0
    List<String> lines = file.readLines()

    for (int i = 0; i < lines.size(); i++) {
        String line = lines[i]
        if (IGNORE_IMPORTS.any { line.startsWith(it) }) {
            toDelete << i
            continue
        }
        if (!classLine && line.contains('class ')) {
            classLine = i
            continue
        }

        if (!importLine && line.contains('import')) {
            importLine = i
        }
        if (line.startsWith('@Integration')) {
            extraImports.remove('grails.testing.mixin.integration.Integration')
            extraAnnotations.remove('@Integration')
            continue
        }
        if (line.startsWith('@Rollback')) {
            extraImports.remove('grails.gorm.transactions.Rollback')
            extraAnnotations.remove('@Rollback')
        }
    }

    toDelete.sort().reverse(false).each {
        lines.remove(it)
    }
    if (extraImports) {
        lines.addAll(importLine, extraImports.collect { "import $it" })
    }
    if (extraAnnotations) {
        lines.addAll(classLine + extraImports.size(), extraAnnotations.collect { it })
    }
    println "classLine:        $classLine"
    println "toDelete:         $toDelete"
    println "extraImports:     $extraImports"
    println "extraAnnotations: $extraAnnotations"
    file.text = lines.join('\n')
}

return null

