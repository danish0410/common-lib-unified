package org.commonlibunified

import groovy.json.JsonSlurper

class ApplicationBuilder implements Serializable {
    def steps

    String repoName
    String appType
    String appTypeKey
    String imageName
    String containerName
    String dockerPort
    String hostPort
    String envStage
    Map parsedMap
    Map repoConfig

    ApplicationBuilder(steps) {
        this.steps = steps
    }

    void cleanWorkspace() {
        steps.echo "🫉 Cleaning workspace..."
        if (steps.isUnix()) {
            steps.sh 'rm -rf *'
        } else {
            steps.bat 'del /F /Q *.* >nul 2>&1'
        }
        steps.echo "✅ Workspace cleaned."
    }

    void initialize() {
        try {
            repoName = steps.params.REPO_NAME
            if (!repoName?.trim()) steps.error("❌ 'REPO_NAME' must be provided.")

            def configText = steps.libraryResource("common-repo-list.js")
            steps.writeFile(file: "common-repo-list.js", text: configText)

            parsedMap = parseAndNormalizeJson(configText)
            appTypeKey = findAppType(repoName, parsedMap)
            if (!appTypeKey) steps.error("❌ Repository '${repoName}' not found in config.")

            appType = appTypeKey.toLowerCase()
            repoConfig = parsedMap[appTypeKey].find { it['repo-name'] == repoName }

            def isEureka = (appType == 'eureka')
            dockerPort = getDefaultDockerPort(appType)
            hostPort = findAvailablePortForType(appType)
            if (!hostPort) steps.error("❌ No available port found for type '${appType}'.")

            imageName = "${repoName.toLowerCase()}-image"
            containerName = "${repoName.toLowerCase()}-container"
            envStage = steps.params.ENV_STAGE ?: 'dev'

            steps.env.APP_TYPE = appType
            steps.env.PROJECT_DIR = repoName
            steps.env.IMAGE_NAME = imageName
            steps.env.CONTAINER_NAME = containerName
            steps.env.DOCKER_PORT = dockerPort
            steps.env.HOST_PORT = hostPort
            steps.env.ENV_STAGE = envStage

            steps.echo "✅ Environment initialized for '${repoName}' as '${appType}' on port ${hostPort}"
        } catch (Exception e) {
            steps.error("❌ InitEnv failed: ${e.message ?: e.toString()}")
        }
    }

    void checkout(String branch = 'feature', int timeout = 20) {
        steps.checkout([
            $class: 'GitSCM',
            branches: [[name: "*/${branch}"]],
            extensions: [
                [$class: 'CloneOption', timeout: timeout, shallow: false],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: "target-repo/${repoName}"]
            ],
            userRemoteConfigs: [[
                url: repoConfig["git-url"],
                credentialsId: repoConfig["git_credentials_id"]
            ]]
        ])
    }

    void preRunDebug() {
        steps.echo "🔧 APP_TYPE       = '${steps.env.APP_TYPE}'"
        steps.echo "🔧 IMAGE_NAME     = '${steps.env.IMAGE_NAME}'"
        steps.echo "🔧 CONTAINER_NAME = '${steps.env.CONTAINER_NAME}'"
        if (!steps.env.APP_TYPE) {
            steps.error "❌ APP_TYPE is null or not initialized!"
        }
    }

    void build(String branch) {
        steps.echo "⚙️ build() invoked"
        def basePath = "target-repo/${repoName}"
        steps.dir(basePath) {
            switch (appType) {
                case 'springboot': buildSpringBootApp(); break
                case 'nodejs':     buildNodeApp(); break
                case 'python':     buildPythonApp(); break
                case 'ruby':       buildRubyApp(); break
                case 'nginx':
                case 'php':        buildStaticApp(); break
                default:           steps.error("❌ Unsupported appType: ${appType}")
            }
        }
    }

    private void buildSpringBootApp() {
        def pom = steps.findFiles(glob: '**/pom.xml')
        if (!pom) steps.error("❌ pom.xml not found.")
        def pomPath = pom[0].path.replaceAll('\\\\', '/')
        def dir = pomPath.contains('/') ? pomPath.substring(0, pomPath.lastIndexOf('/')) : '.'
        steps.dir(dir) {
            runCommand('mvn clean install -DskipTests')
            runCommand('mvn package -DskipTests')
            checkDockerfileExists()
            runCommand("docker build -t ${imageName}:latest .")
        }
    }

    private void buildNodeApp() {
        runCommand('npm install')
        runCommand('npm run build || echo "⚠️ No build step defined."')
        checkDockerfileExists()
        runCommand("docker build -t ${imageName}:latest .")
    }

    private void buildPythonApp() {
        runCommand('pip install -r requirements.txt || echo "⚠️ requirements.txt missing."')
        checkDockerfileExists()
        runCommand("docker build -t ${imageName}:latest .")
    }

    private void buildRubyApp() {
        runCommand('bundle install || echo "⚠️ bundle install failed."')
        checkDockerfileExists()
        runCommand("docker build -t ${imageName}:latest .")
    }

    private void buildStaticApp() {
        checkDockerfileExists()
        runCommand("docker build -t ${imageName}:latest .")
    }

    void runContainer() {
        if (!containerName || !imageName || !hostPort || !dockerPort || !appType)
            steps.error("❌ Missing required parameters.")

        runCommand("docker stop ${containerName} || exit 0")
        runCommand("docker rm ${containerName} || exit 0")

        def runCmd = (appType == "springboot") ?
            "docker run -d --name ${containerName} --network spring-net -p ${hostPort}:${dockerPort} ${imageName}:latest --server.port=${dockerPort} --spring.datasource.url=jdbc:mysql://host.docker.internal:3306/world --spring.datasource.username=root --spring.datasource.password=Thani@01 --spring.jpa.hibernate.ddl-auto=update" :
            "docker run -d --name ${containerName} --network spring-net -p ${hostPort}:${dockerPort} ${imageName}:latest"

        runCommand(runCmd)
    }

    void healthCheck() {
        if (!containerName || !appType || !hostPort) {
            steps.echo "⚠️ Skipping health check."
            return
        }
        String url = "http://localhost:${hostPort}${getHealthEndpoint(appType)}"
        performHealthCheck(url, containerName)
    }

    void performHealthCheck(String url, String containerName) {
        try {
            steps.echo "⏳ Starting health check for ${url}"
            steps.sleep(time: 20, unit: 'SECONDS')

            def success = false
            def maxAttempts = 10
            def delaySeconds = 3

            for (int i = 1; i <= maxAttempts; i++) {
                def code = steps.isUnix()
                    ? steps.sh(script: "curl -s -o /dev/null -w \"%{http_code}\" ${url}", returnStdout: true).trim()
                    : extractStatusCode(steps.bat(script: "curl -s -o NUL -w \"%%{http_code}\" ${url}", returnStdout: true))

                steps.echo "🔁 Attempt ${i}: HTTP ${code}"
                if (["200", "403", "302"].contains(code)) {
                    steps.echo "✅ Service healthy with code ${code}"
                    success = true
                    break
                }
                steps.sleep(time: delaySeconds, unit: 'SECONDS')
            }

            if (!success) {
                throw new Exception("Health check failed after ${maxAttempts} attempts")
            }

        } catch (Exception e) {
            steps.echo "❌ Health check failed for ${containerName}"
            runCommand("docker logs ${containerName} || true")
            steps.error("🚨 Health check error: ${e.message}")
        }
    }

    void startMySQLContainer() {
        def cmd = steps.isUnix() ? '''
            docker stop mysql-db || true
            docker rm mysql-db || true
            docker volume create mysql-db-data || true
            docker run -d --name mysql-db --network spring-net -e MYSQL_ROOT_PASSWORD=Thani@01 -v mysql-db-data:/var/lib/mysql mysql:8
        ''' : '''
            docker stop mysql-db || exit 0
            docker rm mysql-db || exit 0
            docker volume create mysql-db-data || exit 0
            docker run -d --name mysql-db --network spring-net -e MYSQL_ROOT_PASSWORD=Thani@01 -v mysql-db-data:/var/lib/mysql mysql:8
        '''
        runCommand(cmd)
    }
    
    private String getDefaultDockerPort(String appType) {
        switch (appType) {
            case 'springboot': return '8080'
            case 'eureka':     return '8761'
            default:           return '80'
        }
    }
    
    private String getHealthEndpoint(String appType) {
        switch (appType) {
            case 'springboot': return "/actuator/health"
            default:           return "/"
        }
    }
    
    private void checkDockerfileExists() {
        def found = steps.findFiles(glob: '**/Dockerfile')
        if (!found || found.size() == 0) {
            steps.error("❌ Dockerfile not found.")
        }
    }
    
    private void runCommand(String command) {
        steps.echo "▶️ ${command}"
        if (steps.isUnix()) {
            steps.sh(command)
        } else {
            steps.bat(command)
        }
    }
    
    private String extractStatusCode(String output) {
        def lines = output.readLines().findAll { it.trim() }
        return lines ? lines[-1].trim() : "000"
    }

    @NonCPS
    def parseAndNormalizeJson(String configText) {
        def raw = new JsonSlurper().parseText(configText)
        def normalized = [:]
        raw.each { type, list ->
            normalized[type] = list.collect { item ->
                item instanceof Map ? item.collectEntries { k, v -> [(k): v.toString()] } : item
            }
        }
        return normalized
    }

    @NonCPS
    def findAppType(String repoName, Map parsedMap) {
        parsedMap.find { type, repos -> repos.find { it['repo-name'] == repoName } }?.key
    }

    String findAvailablePortForType(String appType) {
        def (start, end) = (appType == 'nginx' || appType == 'php') ? [8081, 8090] : [9001, 9010]
        def isWindows = !steps.isUnix()

        for (int port = start; port <= end; port++) {
            def cmd = isWindows ? "netstat -an | findstr :${port}" : "netstat -an | grep :${port}"
            def status = isWindows
                ? steps.bat(script: cmd, returnStatus: true)
                : steps.sh(script: cmd, returnStatus: true)
            if (status != 0) return port.toString()
        }
        return null
    }
}
