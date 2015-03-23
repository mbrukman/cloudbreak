#!/bin/bash

build-cloudbreak() {
    ./gradlew clean build
}

run-cloudbreak() {
    if [ "$CB_DEBUG" ]; then
        params="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
    fi
    java $params -jar build/libs/cloudbreak-*.jar
}

main() {
  build-cloudbreak
  run-cloudbreak
}

[[ "$0" == "$BASH_SOURCE" ]] && main "$@"
