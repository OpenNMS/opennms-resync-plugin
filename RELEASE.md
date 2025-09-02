## Resync Release Process:
In this example we are releasing version 1.0.6

1) Check out `release-1.x`
2) run `mvn versions:set -DnewVersion=1.0.6` 
3) modify `docs/antora.yml` and change the version to `version: '1.0.6'`
4) commit changes `git commit -a -m "v1.0.6-SNAPSHOT -> v1.0.6"`
5) tag the release `git tag v1.0.6`
6) push changes `git push && git push --tags`
7) run `mvn versions:set -DnewVersion=1.0.7-SNAPSHOT` 
8) modify `doc/antora.yml` and change the version to `version: '1.0.7-SNAPSHOT'`
9) commit changes `git commit -a -m "v1.0.6 -> v1.0.7-SNAPSHOT"`
10) push changes `git push`
