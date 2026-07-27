#!/bin/bash
CURRENT_VERSION=$(./get-release-version.sh)

./mvnw versions:set -DnewVersion=${CURRENT_VERSION} -DgenerateBackupPoms=false
sed -i '' -e "s|<redis-adapter.version>.*</redis-adapter.version>|<redis-adapter.version>${CURRENT_VERSION}</redis-adapter.version>|" examples/*/pom.xml
git add pom.xml */pom.xml examples/*/pom.xml
git commit -m "Bump to ${CURRENT_VERSION}"