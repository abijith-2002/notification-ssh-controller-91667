#!/bin/bash
cd /home/kavia/workspace/code-generation/notification-ssh-controller-91667/notification_listener_frontend
./gradlew lint
LINT_EXIT_CODE=$?
if [ $LINT_EXIT_CODE -ne 0 ]; then
   exit 1
fi

