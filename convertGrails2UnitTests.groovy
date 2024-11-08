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
    header = "'directory' is the source directory update unittests. If not given, the process stops"
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
        'import grails.web.taglib.exceptions.GrailsTagException',
        'import grails.plugin.spock.TagLibSpec',
        'import grails.plugin.spock.UnitSpec',
        'import grails.test.mixin',
        'import grails.buildtestdata.mixin'
]

static List<String> extractMocks(String line, String prefix) {
    String mocks = line - prefix - '(' - ')' - '[' - ']'
    return mocks.split(',').collect { it.trim() }
}

directory.eachFileRecurse { File file ->
    if (file.directory) return
    if (!file.name.endsWith('.groovy')) return
    println "File: ${file.absolutePath - directory.absolutePath}"
    String testFor = ''
    List<String> mocks = []
    List<Integer> toDelete = []
    List<String> extraImports = []
    List<String> extraImplements = []
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
        if (line.startsWith('@Build')) {
            while (!line.endsWith(')')) {
                mocks += extractMocks(line, '@Build')
                toDelete << i++
                line = lines[i]
            }
            mocks += extractMocks(line, '@Build')
            toDelete << i
            continue
        }
        if (line.startsWith('@Mock')) {
            while (!line.endsWith(')')) {
                mocks += extractMocks(line, '@Mock')
                toDelete << i++
                line = lines[i]
            }
            mocks += extractMocks(line, '@Mock')
            toDelete << i
            continue
        }
        if (line.startsWith('@Mixin')) {
            extraImplements << (line - '@Mixin(' - ')')
            toDelete << i
        }
       if (line.startsWith('@TestMixin')) {
            extraImplements << (line - '@TestMixin(' - 'TestMixin)')
            toDelete << i
        }
        if (line.startsWith('@TestFor')) {
            testFor = line - '@TestFor(' - ')'
            toDelete << i
        }
    }

    if (testFor) {
        if (testFor.endsWith('Service')) {
            extraImplements << "ServiceUnitTest<$testFor>"
            extraImports << 'grails.testing.services.ServiceUnitTest'
            mocks.remove(testFor)

        } else if (testFor.endsWith('Controller')) {
            extraImplements << "ControllerUnitTest<$testFor>"
            extraImports << 'grails.testing.web.controllers.ControllerUnitTest'
            mocks.remove(testFor)
        } else if (testFor.endsWith('TagLib')) {
            extraImplements << "TagLibUnitTest<$testFor>"
            extraImports << 'grails.testing.web.taglib.TagLibUnitTest'
            mocks.remove(testFor)
        } else {
            mocks << testFor
        }
    }

    if (mocks) {
        extraImplements << 'BuildDataTest'
        extraImports << 'grails.buildtestdata.BuildDataTest'
        String extraLines = """\
            |
            |    @Override
            |    Class[] getDomainClassesToMock() { [${mocks.unique().join(',')}] as Class[] }
            |""".stripMargin()
        lines.addAll(classLine + 1, extraLines.split('\n'))
    }


    String tmp = (lines[classLine] - '{').trim()
    if (tmp.contains('spock.lang.Specification')) {
        tmp = tmp.replace('spock.lang.', '')
        extraImports << 'spock.lang.Specification'
    }
    if (extraImplements) {
        tmp += " implements ${extraImplements.join(', ')}"
    }
    lines[classLine] = "$tmp {"

    toDelete.sort().reverse(false).each {
        lines.remove(it)
    }
    if (extraImports) {
        lines.add(1, '')
        lines.addAll(2, extraImports.collect { "import $it" })
    }

    println "classLine:     $classLine"
    println "toDelete:      $toDelete"
    println "mocks:         $mocks"
    println "extraImports:  $extraImports"
    println "extraImpl:     $extraImplements"

    file.text = lines.join('\n')
}

return null

