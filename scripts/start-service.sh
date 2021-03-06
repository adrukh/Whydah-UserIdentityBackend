#!/bin/bash
# Used by https://github.com/Cantara/Whydah/tree/master/dev-quickstart

# If Version is from source, find the artifact
if [ "$Version" = "FROM_SOURCE" ]; then 
    # Find the bult artifact
    Version=$(find target/* -name '*.jar' | grep SNAPSHOT | grep -v javadoc | grep -v original | grep -v lib)
else
    Version=UserIdentityBackend.jar
fi

nohup /usr/bin/java $env_vars  -jar  $Version &
# DEV mode
# nohup /usr/bin/java $env_vars  -DCONSTRETTO_TAGS=DEV -jar  $Version &
