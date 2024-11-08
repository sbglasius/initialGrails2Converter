#!/usr/bin/env groovy
@Grapes([
        @Grab(group = 'org.codehaus.groovy', module = 'groovy-cli-picocli', version = '3.0.23'),
        @Grab('info.picocli:picocli-groovy:4.2.0')
])
@GrabConfig(systemClassLoader = true)
import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

def cli = new CliBuilder(usage: "./${this.class.getSimpleName()}.groovy <directory>")
cli.with {
    header = "'directory' is the source directory to convert. If not given, the process stops"
    h(longOpt: 'help', "Print usage and exit")
    _(longOpt: 'type', args: 1, "Grails create app or plugin (default: plugin)")
    _(longOpt: 'version', args: 1, "Grails version (default: 4.0.10)")
    _(longOpt: 'profile', args: 1, "Grails profile to use (default for plugin: plugin, default for app: web)")
    _(longOpt: 'temp-dir', args: 1, "Directory to use for old grails app (default: pre-upgrade)")
    _(longOpt: 'issue', args: 1, "JIRA issue to prefix commits (default: '')")
    _(longOpt: 'branch', args: 1, 'git branch to create (default: upgrade/grails_${version}')
    _(longOpt: 'no-branch', args: 0, "Do not do a branch, instead work on current branch")
    v(longOpt: 'verbose', "Debug information")
}
//noinspection GroovyAssignabilityCheck
OptionAccessor options = cli.parse(args)
List<String> arguments = options.arguments()

if (options.h || arguments.size() != 1) {
    cli.usage()
    System.exit(0)
}
String type = options.type ?: 'plugin'
String profile = options.profile ?: (type == 'plugin' ? 'plugin' : 'web')
String upgradeDir = options.'temp-dir' ?: 'pre-upgrade'
String issue = options.issue ? "${options.issue} " : ''
String grailsVersion = options.version ? "${options.version}" : '6.2.1'
String grails6 = grailsVersion.startsWith('6.')
String branch = options.branch ?: "upgrade/grails_${grailsVersion}"
boolean noBranch = options.'no-branch' as boolean
boolean verbose = options.verbose as boolean

Path directory = Paths.get(arguments[0]).toAbsolutePath()

if (Files.notExists(directory)) {
    println("${directory.toAbsolutePath()} does not exist")
    System.exit(-1)
}

Path appPropPath = directory.resolve('application.properties')
if (Files.notExists(appPropPath)) {
    println("${appPropPath} does not exist! Are you sure it's a Grails 2 directory?")
    System.exit(-1)
}
Properties appConf = new Properties()
appPropPath.withInputStream { appConf.load(it) }
String appName = appConf.get('app.name')

Path grailsExecutable = Paths.get("${System.getProperty('user.home')}/.sdkman/candidates/grails/${grailsVersion}/bin/grails")
if (Files.notExists(grailsExecutable)) {
    println("${grailsExecutable} does not exist")
    System.exit(-1)
}

if (!options.profile && profile == 'plugin') {
    List<String> webRes = ['assets', 'controllers', 'taglib', 'views'].findResults {
        File f = directory.resolve("grails-app/$it").toFile()
        (f.directory && f.list().length > 0) ? it : null
    }
    if (webRes) {
        println("""Aborting because; '${directory.fileName}/grails-app' contains non-empty $webRes directories! #SanityCheck
It's unlikely that '$profile' profile will support migration to Grails 3/4, consider instead 'rest-plugin', 'web-plugin' etc.
(if you're positive this is a '$profile', override this sanity-check by explicitly defining profile-argument)""")
        System.exit(-1)
    }
}

def exec(String cmd, Path directory, boolean verbose = false) {
    if (verbose) {
        println "Executing: $cmd"
    }
    Process p = ['/bin/bash', '-v', '-c', cmd].execute(null as List, directory.toFile())
    StringWriter outWriter = new StringWriter()
    StringWriter errWriter = new StringWriter()

    p.waitForProcessOutput(outWriter, errWriter)

    String out = outWriter.toString()
    String err = errWriter.toString()
    String result = "$out\n$err"

    if (verbose) {
        println result
    }
    return result
}

def oldDir = directory.resolve(upgradeDir).toAbsolutePath()

println "OldDir: ${oldDir}"
Files.createDirectories(oldDir)

Map<String, String> move = [
        'external-config'                        : 'external-config/',
        'grails-app/controllers'                 : 'grails-app/controllers/',
        'grails-app/services'                    : 'grails-app/services/',
        'grails-app/domain'                      : 'grails-app/domain/',
        'grails-app/assets'                      : 'grails-app/assets/',
        'grails-app/i18n'                        : 'grails-app/i18n/',
        'grails-app/taglib'                      : 'grails-app/taglib/',
        'grails-app/utils'                       : 'grails-app/utils/',
        'grails-app/views'                       : 'grails-app/views/',
        'grails-app/views/layouts'               : 'grails-app/views/layouts/',
        'src/groovy'                             : 'src/main/groovy/',
        'src/docs'                               : 'src/docs/',
        'src/java'                               : 'src/main/java/',
        'test/unit'                              : 'src/test/groovy/',
        'test/integration'                       : 'src/integration-test/groovy/',
        'grails-app/conf/Config.groovy'          : 'grails-app/conf/application.groovy',
        'grails-app/conf/spring/resources.groovy': 'grails-app/conf/spring/resources.groovy',
        'settings.gradle'                        : 'settings.gradle',
        'Jenkinsfile*'                           : '.',
]

List<String> remove = [
        "gradle",
        "wrapper",
        "gradlew*",
        "grailsw*",
]

cmds = """\
        git tag pre-upgrade
        ${noBranch ? '#' : ''}git checkout -b ${branch}
        git mv -vkf * ${upgradeDir}/
        git commit -m '${issue}Temporarily moved files to: ${upgradeDir}'
        ${grailsExecutable} create-${type} ${appName} --inplace ${grails6 ? '': "--profile=${profile}"}
        """.stripIndent()
cmds.eachLine {
    exec(it, directory, verbose)
}
if (Files.notExists(directory.resolve('grails-app'))) {
    println "Grails app not created"
    System.exit(-1)
}
cmds = """
        git add .
        git commit -m '${issue}Created new empty Grails ${grailsVersion} ${type} ${grails6 ? '' : "(${profile} profil)"}'
        """.stripIndent()
cmds.eachLine {
    exec(it, directory, verbose)
}

move.each { src, dest ->
    Path srcFile = oldDir.resolve(src)
    println "Handle $srcFile ->"
    if (Files.notExists(srcFile)) return
    Path destFile = directory.resolve(dest)
    if (Files.notExists(destFile)) {
        if (dest.endsWith('/')) {
            Files.createDirectory(destFile)
        } else {
            Files.createDirectories(destFile.parent)
        }
    }
    if (Files.exists(srcFile)) {
        if (Files.isDirectory(srcFile)) {
            srcFile.eachFileRecurse { file ->
                def path = file.subpath(srcFile.nameCount, file.nameCount)
                if (!Files.isDirectory(file)) {

                    exec("git mv -vfk ${oldDir.fileName}/${src}/$path ${dest}$path", directory, verbose)
                } else {
                    def newDir = destFile.resolve(path)
                    Files.createDirectories(newDir)
                }
//                    exec("git mv -vfk ${upgradeDir}/${src}/${dir.name}/* ${dest}${dir.name}/", directory, verbose)
            }
        } else {
            exec("git mv -vfk ${oldDir.fileName}/$src ${dest}", directory, verbose)
        }
    }

    if (dest == 'grails-app/conf/application.groovy') {
        println "Document application groovy vs. yml usage: $destFile --------------->"
        String topComment = '''/**
                             | * Configuration of module test environment.
                             | * Neither application.groovy nor application.yml will be included in published module artefact!
                             | *
                             | * application.yml loads first, then loads application.groovy (and overwrites any) - last loaded configuration wins
                             | *
                             | * application.yml is based on Grails 3/4 because Spring uses yml - yml configuration should not be affected for future upgrades. #Vanilla
                             | * For backwards compatibility, Grails 3/4 supports application.groovy - this is used when upgrading thus minimizing the amount of changes.
                             | */'''.stripMargin()
        File f = destFile.toFile()
        f.text = "$topComment\n${f.exists() ? f.text : ''}"
        exec("git add '$destFile'", directory, verbose)
    }
}

exec("git commit -m '${issue}Moving files back to Grails ${type}'", directory, verbose)
remove.each {
    exec("git rm ${upgradeDir}/${it}", directory, verbose)
}
exec("git commit -m '${issue}Removing not needed files'", directory, verbose)
exec("git clean -d -f", directory, verbose)

// ----------------------- migrating Groovy files, BEWARE: order the most specific "replace" first and least specific "replace" last!

// migrate import, references etc.
replaceMap = [
        // FROM -> TO
        'import org.codehaus.groovy.grails.web.util.WebUtils'               : 'import org.grails.web.util.WebUtils',
        'import grails.transaction.Transactional'                           : 'import grails.gorm.transactions.Transactional',
        'import org.codehaus.groovy.grails.web.servlet'                     : 'import org.grails.web.servlet',
        'import org.codehaus.groovy.grails.web'                             : 'import grails.web',
        'import org.codehaus.groovy.grails.commons'                         : 'import grails.core',
        'import org.codehaus.groovy.grails'                                 : 'import org.grails',
        'import grails.test.spock.IntegrationSpec'                          : 'import spock.lang.Specification\nimport grails.testing.mixin.integration.Integration',
        'extends IntegrationSpec'                                           : 'extends Specification',
// Log4j -> Slf4j (Logback)
        'import groovy.util.logging.Log4j'                                  : 'import groovy.util.logging.Slf4j',
        '@Log4j'                                                            : '@Slf4j',
        'import org.apache.log4j.Logger'                                    : 'import org.slf4j.Logger',
        'import org.apache.log4j.MDC'                                       : 'import org.slf4j.MDC',
        'import org.apache.commons.logging.Log'                             : 'import org.slf4j.Logger',
        'import org.apache.commons.logging.LogFactory'                      : 'import org.slf4j.LoggerFactory',
        'import grails.core.InstanceFactoryBean'                            : 'import org.grails.spring.beans.factory.InstanceFactoryBean',
        'import org.grails.databinding.events.DataBindingListenerAdapter'   : 'import grails.databinding.events.DataBindingListenerAdapter',
        'org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes': 'import org.grails.web.util.GrailsApplicationAttributes',
        'import org.codehaus.groovy.grails.web.util.WebUtils'               : 'import org.grails.web.util.WebUtils',
        'Log log = LogFactory.getLog'                                       : 'Logger log = LoggerFactory.getLogger',
        'gormVersion=6.1.10.BUILD-SNAPSHOT'                                 : 'gormVersion=6.1.10.RELEASE' // Grails 3.3.6 work-around https://github.com/grails/grails-core/issues/11033
]

String handleReplace(String line) {
    return replaceMap.findAll { contains, replace -> line.contains(contains) }.inject(line) { l, contains, replace ->
        return l.replaceAll(contains, replace)
    }
}
// migrate new references
prefixLine = [
        'extends IntegrationSpec': '@Integration'
]

String handlePrefix(String line) {
    return prefixLine.findAll { contains, insert -> line.contains(contains) }.inject(line) { l, start, insert ->
        return "$insert\n$l"
    }
}

List<String> handleValidateable(List<String> lines) {
    Pattern validateableLine = ~/@Validateable(\(nullable\s*=\s*(?:true|false)\))?/
    Pattern classLine = ~/(.*class [\w\d]+)( extends [\.\w\d<>]+)?( implements [\w\d.,<>]+)?\s*\{\s*/
    if (lines.any { it.contains('@Validateable') }) {
        def nullable = false
        def importNullable = false
        def hasAnnotation = false
        lines = lines.inject([]) { newLines, line ->
            def match = validateableLine.matcher(line)
            if (match.matches()) { // Check if it's nullable = true
                nullable = (match[0][1])?.contains('true')
                importNullable = importNullable || nullable
                hasAnnotation = true
                return newLines
            }
            match = classLine.matcher(line)
            if (hasAnnotation && match.matches()) { // Rewrite class definition line
                String classDef = match[0][1]
                String classExt = match[0][2]
                String classImp = (match[0][3] ? match[0][3] + ', ' : ' implements ') + 'Validateable' + (nullable ? ', DefaultNullableTrue' : '')
                newLines << "${classDef}${classExt ?: ''}${classImp} {"
                // Reset hasAnnotation, if lines contains more than one class
                hasAnnotation = false
                nullable = false
            } else {
                newLines << line
            }

            return newLines
        } as List<String>
        if (importNullable) { // Insert import for DefaultNullableTrue
            lines = lines.inject([]) { newLines, line ->
                newLines << line
                if (line == 'import grails.validation.Validateable') {
                    return newLines << 'import support.grails.validation.DefaultNullableTrue'
                }
                return newLines
            }
        }
    }
    return lines
}

// perform migration
directory.eachFileRecurse { Path file ->
    String fileName = file.toAbsolutePath().toString()
    if (!Files.isDirectory(file) && !fileName.contains('.git') && !fileName.contains(upgradeDir) && fileName.endsWith('.groovy')) {
        List<String> lines = file.readLines('UTF-8').collect { line ->
            line = handlePrefix(line)
            line = handleReplace(line)
            return line
        }

        lines = handleValidateable(lines)

        String output = lines.join('\n') + '\n'

        file.setText(output, 'UTF-8')
    }
}

exec("git commit -a -m '${issue}Simple modifications to imports, tests and command objects'", directory, verbose)

println "Done, but you still need to do a lot of manual work, like setting up dependencies, convert tests and so on."
println ""
println "Hope you get it working. Don't be afraid to ask for help in the Grails Community Slack!"
