#!/bin/bash

for i in $(cat all_tests.txt); do
 echo "mvn -Dtest=$i test"
       mvn -Dtest=$i test
done

