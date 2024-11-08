# Initial Grails 2 convert to Grails 3+ (Default 6.2.1)

**DISCLAIMER:** Use at your own risk!

This groovy script will take your Grails 2 application (or plugin) in a `git` repository and do the initial conversion to Grails 6, by moving a lot of files around, and change some (but not all) imports to the new package structure. It is by no means perfect, but will save a couple of hours of work, and will retain the history of your git commits.

## Prerequisite:

* [sdkman.io](http://sdkman.io) installed
* Your project must be in `git`

## Setup:

Install the needed java, groovy and grails versions by using

```bash
sdk install java 11.0.24-tem 
sdk install groovy 3.0.23
sdk install grails 6.2.1
```

```bash
sdk use java 11.0.24-tem 
sdk use groovy 3.0.23
sdk use grails 6.2.1
```

make a working directory for the upgrade:

```bash
mkdir workdir
cd workdir
```

Download the above script to a working directory: `initialGrails2Converter.groovy`
and change the permissions with

```bash
chmod +x initialGrails2Converter.groovy
```

I suggest checking out the repository you want to convert into a sub-directory of your work directory:

```bash
git clone your-repository-url application
cd application
```

If you're upgrading an application this is an example of starting the upgrade:

```bash
./initialGrails2Converter.groovy --type=app --version=6.2.1 --verbose application
``` 

## Need help or have improvements?

**bugreports, suggestions or pull-requests are welcome**

Discussion is open to suggest help to others.

If you use it, give it a star here on GitHub

Don't be afraid to ask for help in the Grails Community Slack!




