// Jenkins 任务 GO-IM-KMP 的任务脚本（Pipeline script，非 SCM）：在 node-mac 上检出指定分支后执行 ci/release.groovy。
// 修改后需同步到 Jenkins 任务配置；构建逻辑本身改 ci/release.groovy 即可，无需改任务。

node('node-mac') {
    try {
        stage('Checkout') {
            deleteDir()
            retry(3) {
                def scmVars = checkout([
                    $class: 'GitSCM',
                    branches: [[name: "*/${params.BRANCH}"]],
                    userRemoteConfigs: [[
                        url: 'https://github.com/chaishuanzhu/GO-IM.git',
                        refspec: "+refs/heads/${params.BRANCH}:refs/remotes/origin/${params.BRANCH}",
                    ]],
                    extensions: [[$class: 'CloneOption', shallow: true, depth: 1, noTags: true, honorRefspec: true, timeout: 20]],
                ])
                env.GIT_COMMIT = scmVars.GIT_COMMIT
            }
        }
        load 'kmp/ci/release.groovy'
    } finally {
        deleteDir()
    }
}
