// Jenkins job go-im-tags (Pipeline script): polls GitHub for new kmp-v<MAJOR.MINOR.PATCH> tags and starts
// go-im with TAG=<tag> for each one. Jenkins is LAN-only, so GitHub webhooks cannot reach it.
// Known tags are kept on ci-01 in /data/jenkins-agent/state/goim-kmp-tags.txt; the first run only records the existing
// tags. Delete a line from that file to release that tag again on the next poll.

def repoUrl = 'https://github.com/chaishuanzhu/GO-IM.git'

node('docker') {
    def remote = ''
    retry(3) {
        remote = sh(returnStdout: true, script: "git ls-remote --tags --refs ${repoUrl} 'refs/tags/kmp-v*'").trim()
    }
    def current = remote ? remote.readLines().collect { it.replaceFirst(/.*refs\/tags\//, '') }.findAll { it ==~ /kmp-v[1-9][0-9]*\.[0-9]+\.[0-9]+/ } : []
    dir('/data/jenkins-agent/state') {
        if (!fileExists('goim-kmp-tags.txt')) {
            writeFile file: 'goim-kmp-tags.txt', text: current.join('\n') + '\n'
            currentBuild.description = "baseline: ${current.join(', ') ?: '(none)'}"
            return
        }
        def known = readFile('goim-kmp-tags.txt').readLines().collect { it.trim() }.findAll { it }
        def fresh = current.findAll { !known.contains(it) }
        if (!fresh) {
            currentBuild.description = 'no new tags'
            currentBuild.result = 'NOT_BUILT'
            return
        }
        for (t in fresh) {
            build job: 'go-im', wait: false, parameters: [string(name: 'TAG', value: t)]
            echo "started go-im for ${t}"
        }
        writeFile file: 'goim-kmp-tags.txt', text: (known + fresh).join('\n') + '\n'
        currentBuild.description = "started go-im: ${fresh.join(', ')}"
    }
}
