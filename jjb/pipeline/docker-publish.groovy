/* docker-publish pipeline */
pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }
  stages {

    stage('checkout') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "${params.gitUrl}",
            name: "${params.gitRef}",
          ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.GERRIT_PROJECT}"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          git_tags = sh(script:"cd $GERRIT_PROJECT; git tag -l --points-at HEAD", returnStdout: true).trim()
        }
      }
    }

    stage('build'){
      steps {
        sh( script: """
          #!/usr/bin/env bash
          set -eu -o pipefail

          # checked out in a subdir so the log can be in WORKSPACE
          cd "$GERRIT_PROJECT"

          # set registry/repository variables
          export DOCKER_REGISTRY="$dockerRegistry"
          export DOCKER_REPOSITORY="$dockerRepo/"

          # Build w/branch
          echo "Building image with branch"
          make DOCKER_TAG="$GERRIT_BRANCH" docker-build 2>&1 | tee "$WORKSPACE/docker-build.log"

          # Build w/tags if they exist
          if [ -n "$git_tags" ]
          echo "Tags found in git, building:"
          echo "$git_tags"

          then
            for tag in $git_tags
            do
              echo "Building image with tag: \$tag (should reuse cached layers)"
              make DOCKER_TAG="\$tag" docker-build
            done
          fi
        """)
      }
    }

    stage('push'){
      steps {
        script {
          withDockerRegistry([credentialsId: 'docker-artifact-push-credentials']) {
            sh( script:"""
              #!/usr/bin/env bash
              set -eu -o pipefail

              # checked out in a subdir so the log can be in WORKSPACE
              cd "$GERRIT_PROJECT"

              # set registry/repository variables
              export DOCKER_REGISTRY="$dockerRegistry"
              export DOCKER_REPOSITORY="$dockerRepo/"

              # Push w/branch
              echo "Pushing image with branch"
              make DOCKER_TAG="$GERRIT_BRANCH" docker-push 2>&1 | tee  "$WORKSPACE/docker-push.log"

              # Push w/tags if they exist
              if [ -n "$git_tags" ]
              echo "Tags found in git, pushing:"
              echo "$git_tags"
              then
                for tag in $git_tags
                do
                  echo "Pushing image with tag: \$tag (should reuse cached layers)"
                  make DOCKER_TAG="\$tag" docker-push
                done
              fi
            """)
          }
        }
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'docker-*.log', fingerprint: true
      deleteDir()
    }
  }
}