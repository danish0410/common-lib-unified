package org.commonlibunified

import spock.lang.Specification

class ApplicationBuilderSpec extends Specification {

    def steps = Mock()
    def builder = new ApplicationBuilder(steps: steps)

    def "cleanWorkspace() should run rm -rf * on Unix"() {
        given:
        steps.isUnix() >> true

        when:
        builder.cleanWorkspace()

        then:
        1 * steps.echo("🫉 Cleaning workspace...")
        1 * steps.sh("rm -rf *")
        0 * steps.bat(_)
        1 * steps.echo("✅ Workspace cleaned.")
    }

    def "cleanWorkspace() should run del command on Windows"() {
        given:
        steps.isUnix() >> false

        when:
        builder.cleanWorkspace()

        then:
        1 * steps.echo("🫉 Cleaning workspace...")
        1 * steps.bat("del /F /Q *.* >nul 2>&1")
        0 * steps.sh(_)
        1 * steps.echo("✅ Workspace cleaned.")
    }
}
