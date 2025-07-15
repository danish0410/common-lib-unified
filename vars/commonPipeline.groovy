@Library("common-lib-unified@${LIBRARY_BRANCH}") _
import org.commonlibunified.*

def app = null

properties([
  parameters([
    string(name: 'REPO_NAME', defaultValue: 'springboot-role', description: 'Repository Name to checkout'),
    string(name: 'REPO_BRANCH', defaultValue: 'main', description: 'Branch to checkout'),
    choice(name: 'ENV_STAGE', choices: ['dev', 'staging', 'prod'], description: 'Deployment Environment'),
    string(name: 'LIBRARY_BRANCH', defaultValue: 'feature', description: 'Branch of Shared Library (common-lib-unified)')
  ])
])

pipeline {
  agent any

  tools {
    git 'Git'  // Ensure this matches Jenkins tool config
  }

  stages {
    stage('Startup Debug') {
      steps {
        echo "⚙️ REPO_NAME: ${params.REPO_NAME}"
        echo "⚙️ REPO_BRANCH: ${params.REPO_BRANCH}"
        echo "⚙️ ENV_STAGE: ${params.ENV_STAGE}"
        echo "📚 LIBRARY_BRANCH: ${params.LIBRARY_BRANCH}"
      }
    }

    stage('Initialize Environment') {
      steps {
        script {
          app = new ApplicationBuilder(this)
          app.initialize(params.REPO_NAME, params.ENV_STAGE)
        }
      }
    }

    stage('Clean Workspace') {
      steps {
        script {
          app.cleanWorkspace()
        }
      }
    }

    stage('Checkout Repo') {
      steps {
        script {
          app.checkout(params.REPO_BRANCH)
        }
      }
    }

    stage('Build Application') {
      steps {
        script {
          app.build(params.REPO_BRANCH)
        }
      }
    }

    stage('Pre-Run Debug') {
      steps {
        script {
          app.preRunDebug()
        }
      }
    }

    stage('Start MySQL (if needed)') {
      when {
        expression { app?.appType == 'springboot' }
      }
      steps {
        script {
          app.startMySQLContainer()
        }
      }
    }

    stage('Run Container') {
      steps {
        script {
          app.runContainer()
        }
      }
    }

    stage('Health Check') {
      steps {
        script {
          app.healthCheck()
        }
      }
    }

    stage('Success') {
      steps {
        echo "🎉 Deployment successful for ${params.REPO_NAME} [${params.REPO_BRANCH}] in ${params.ENV_STAGE}"
      }
    }
  }

  post {
    failure {
      echo '❌ Deployment failed. Check logs.'
    }
    always {
      echo '🎯 Pipeline execution completed.'
    }
  }
}
